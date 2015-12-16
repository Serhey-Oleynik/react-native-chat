/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.flat;

import com.facebook.csslayout.CSSNode;
import com.facebook.react.uimanager.CatalystStylesDiffMap;
import com.facebook.react.uimanager.ReactShadowNode;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewGroupManager;
import com.facebook.react.uimanager.ViewManager;

/* package */ final class AndroidView extends FlatShadowNode {

  final ViewManager mViewManager;
  private final ReactShadowNode mReactShadowNode;
  private final boolean mNeedsCustomLayoutForChildren;

  /* package */ AndroidView(ViewManager viewManager) {
    mViewManager = viewManager;
    ReactShadowNode reactShadowNode = viewManager.createShadowNodeInstance();
    if (reactShadowNode instanceof CSSNode.MeasureFunction) {
      mReactShadowNode = reactShadowNode;
      setMeasureFunction((CSSNode.MeasureFunction) reactShadowNode);
    } else {
      mReactShadowNode = null;
    }

    if (viewManager instanceof ViewGroupManager) {
      ViewGroupManager viewGroupManager = (ViewGroupManager) viewManager;
      mNeedsCustomLayoutForChildren = viewGroupManager.needsCustomLayoutForChildren();
    } else {
      mNeedsCustomLayoutForChildren = false;
    }

    forceMountToView();
  }

  /* package */ boolean needsCustomLayoutForChildren() {
    return mNeedsCustomLayoutForChildren;
  }

  @Override
  public void setBackgroundColor(int backgroundColor) {
    // suppress, this is handled by a ViewManager
  }

  @Override
  public void setThemedContext(ThemedReactContext themedContext) {
    super.setThemedContext(themedContext);

    if (mReactShadowNode != null) {
      mReactShadowNode.setThemedContext(themedContext);
    }
  }

  @Override
  /* package*/ void handleUpdateProperties(CatalystStylesDiffMap styles) {
    if (mReactShadowNode != null) {
      mReactShadowNode.updateProperties(styles);
    }
  }

  @Override
  public void addChildAt(CSSNode child, int i) {
    super.addChildAt(child, i);
    ((FlatShadowNode) child).forceMountToView();
  }
}
