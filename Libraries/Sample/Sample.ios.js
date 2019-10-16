/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
 * @format
 * @flow strict-local
 */

// Stub of Sample for Android.

'use strict';

const NativeSample = require('../BatchedBridge/NativeModules').Sample;

/**
 * High-level docs for the Sample iOS API can be written here.
 */

const Sample = {
  test: function() {
    NativeSample.test();
  },
};

module.exports = Sample;
