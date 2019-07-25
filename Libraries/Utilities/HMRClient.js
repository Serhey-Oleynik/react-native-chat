/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * @format
 * @flow
 */
'use strict';

const Platform = require('./Platform');
const invariant = require('invariant');
const prettyFormat = require('pretty-format');

const MetroHMRClient = require('metro/src/lib/bundle-modules/HMRClient');

import NativeRedBox from '../NativeModules/specs/NativeRedBox';

import type {ExtendedError} from '../Core/Devtools/parseErrorStack';

const pendingEntryPoints = [];
let hmrClient = null;
let hmrUnavailableReason: string | null = null;
let currentCompileErrorMessage: string | null = null;
let didConnect: boolean = false;

type LogLevel =
  | 'trace'
  | 'info'
  | 'warn'
  | 'log'
  | 'group'
  | 'groupCollapsed'
  | 'groupEnd'
  | 'debug';

export type HMRClientNativeInterface = {|
  enable(): void,
  disable(): void,
  registerBundle(requestUrl: string): void,
  log(level: LogLevel, data: Array<mixed>): void,
  setup(
    platform: string,
    bundleEntry: string,
    host: string,
    port: number | string,
    isEnabled: boolean,
  ): void,
|};

/**
 * HMR Client that receives from the server HMR updates and propagates them
 * runtime to reflects those changes.
 */
const HMRClient: HMRClientNativeInterface = {
  enable() {
    if (hmrUnavailableReason !== null) {
      // If HMR became unavailable while you weren't using it,
      // explain why when you try to turn it on.
      // This is an error (and not a warning) because it is shown
      // in response to a direct user action.
      throw new Error(hmrUnavailableReason);
    }

    invariant(hmrClient, 'Expected HMRClient.setup() call at startup.');
    const LoadingView = require('./LoadingView');

    // We use this for internal logging only.
    // It doesn't affect the logic.
    hmrClient.send(JSON.stringify({type: 'log-opt-in'}));

    // When toggling Fast Refresh on, we might already have some stashed updates.
    // Since they'll get applied now, we'll show a banner.
    const hasUpdates = hmrClient.hasPendingUpdates();

    if (hasUpdates) {
      LoadingView.showMessage('Refreshing...', 'refresh');
    }
    try {
      hmrClient.enable();
    } finally {
      if (hasUpdates) {
        LoadingView.hide();
      }
    }

    // There could be a compile error while Fast Refresh was off,
    // but we ignored it at the time. Show it now.
    showCompileError();
  },

  disable() {
    invariant(hmrClient, 'Expected HMRClient.setup() call at startup.');
    hmrClient.disable();
  },

  registerBundle(requestUrl: string) {
    invariant(hmrClient, 'Expected HMRClient.setup() call at startup.');
    pendingEntryPoints.push(requestUrl);
    registerBundleEntryPoints(hmrClient);
  },

  log(level: LogLevel, data: Array<mixed>) {
    try {
      if (hmrClient) {
        hmrClient.send(
          JSON.stringify({
            type: 'log',
            level,
            data: data.map(message =>
              typeof message === 'string'
                ? message
                : prettyFormat(message, {
                    escapeString: true,
                    highlight: true,
                    maxDepth: 3,
                    min: true,
                    plugins: [prettyFormat.plugins.ReactElement],
                  }),
            ),
          }),
        );
      }
    } catch (error) {
      // If sending logs causes any failures we want to silently ignore them
      // to ensure we do not cause infinite-logging loops.
    }
  },

  // Called once by the bridge on startup, even if Fast Refresh is off.
  // It creates the HMR client but doesn't actually set up the socket yet.
  setup(
    platform: string,
    bundleEntry: string,
    host: string,
    port: number | string,
    isEnabled: boolean,
  ) {
    invariant(platform, 'Missing required parameter `platform`');
    invariant(bundleEntry, 'Missing required paramenter `bundleEntry`');
    invariant(host, 'Missing required paramenter `host`');
    invariant(!hmrClient, 'Cannot initialize hmrClient twice');

    // Moving to top gives errors due to NativeModules not being initialized
    const LoadingView = require('./LoadingView');

    const wsHost = port !== null && port !== '' ? `${host}:${port}` : host;
    const client = new MetroHMRClient(`ws://${wsHost}/hot`);
    hmrClient = client;

    pendingEntryPoints.push(
      `ws://${wsHost}/hot?bundleEntry=${bundleEntry}&platform=${platform}`,
    );

    client.on('connection-error', e => {
      let error = `Cannot connect to the Metro server.

Try the following to fix the issue:
- Ensure that the Metro server is running and available on the same network`;

      if (Platform.OS === 'ios') {
        error += `
- Ensure that the Metro server URL is correctly set in AppDelegate`;
      } else {
        error += `
- Ensure that your device/emulator is connected to your machine and has USB debugging enabled - run 'adb devices' to see a list of connected devices
- If you're on a physical device connected to the same machine, run 'adb reverse tcp:8081 tcp:8081' to forward requests from your device
- If your device is on the same Wi-Fi network, set 'Debug server host & port for device' in 'Dev settings' to your machine's IP address and the port of the local dev server - e.g. 10.0.1.1:8081`;
      }

      error += `

URL: ${host}:${port}

Error: ${e.message}`;

      setHMRUnavailableReason(error);
    });

    client.on('update-start', ({isInitialUpdate}) => {
      currentCompileErrorMessage = null;
      didConnect = true;

      if (client.isEnabled() && !isInitialUpdate) {
        LoadingView.showMessage('Refreshing...', 'refresh');
      }
    });

    client.on('update', ({isInitialUpdate}) => {
      if (client.isEnabled() && !isInitialUpdate) {
        dismissRedbox();
      }
    });

    client.on('update-done', () => {
      LoadingView.hide();
    });

    client.on('error', data => {
      LoadingView.hide();

      if (data.type === 'GraphNotFoundError') {
        client.close();
        setHMRUnavailableReason(
          'The Metro server has restarted since the last edit. Reload to reconnect.',
        );
      } else if (data.type === 'RevisionNotFoundError') {
        client.close();
        setHMRUnavailableReason(
          'The Metro server and the client are out of sync. Reload to reconnect.',
        );
      } else {
        currentCompileErrorMessage = `${data.type} ${data.message}`;
        if (client.isEnabled()) {
          showCompileError();
        }
      }
    });

    client.on('close', data => {
      LoadingView.hide();
      setHMRUnavailableReason(
        'Disconnected from the Metro server. Reload to reconnect.',
      );
    });

    if (isEnabled) {
      HMRClient.enable();
    } else {
      HMRClient.disable();
    }

    registerBundleEntryPoints(hmrClient);
  },
};

function setHMRUnavailableReason(reason) {
  invariant(hmrClient, 'Expected HMRClient.setup() call at startup.');
  if (hmrUnavailableReason !== null) {
    // Don't show more than one warning.
    return;
  }
  hmrUnavailableReason = reason;

  // We only want to show a warning if Fast Refresh is on *and* if we ever
  // previously managed to connect successfully. We don't want to show
  // the warning to native engineers who use cached bundles without Metro.
  if (hmrClient.isEnabled() && didConnect) {
    console.warn(reason);
    // (Not using the `warning` module to prevent a Buck cycle.)
  }
}

function registerBundleEntryPoints(client) {
  if (pendingEntryPoints.length > 0) {
    client.send(
      JSON.stringify({
        type: 'register-entrypoints',
        entryPoints: pendingEntryPoints,
      }),
    );
    pendingEntryPoints.length = 0;
  }
}

function dismissRedbox() {
  if (
    Platform.OS === 'ios' &&
    NativeRedBox != null &&
    NativeRedBox.dismiss != null
  ) {
    NativeRedBox.dismiss();
  } else {
    const NativeExceptionsManager = require('../Core/NativeExceptionsManager')
      .default;
    NativeExceptionsManager &&
      NativeExceptionsManager.dismissRedbox &&
      NativeExceptionsManager.dismissRedbox();
  }
}

function showCompileError() {
  if (currentCompileErrorMessage === null) {
    return;
  }

  // Even if there is already a redbox, syntax errors are more important.
  // Otherwise you risk seeing a stale runtime error while a syntax error is more recent.
  dismissRedbox();

  const message = currentCompileErrorMessage;
  currentCompileErrorMessage = null;

  const error: ExtendedError = new Error(message);
  // Symbolicating compile errors is wasted effort
  // because the stack trace is meaningless:
  error.preventSymbolication = true;
  throw error;
}

module.exports = HMRClient;
