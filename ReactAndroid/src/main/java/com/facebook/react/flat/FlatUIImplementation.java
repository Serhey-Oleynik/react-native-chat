/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.flat;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.uimanager.ReactStylesDiffMap;
import com.facebook.react.uimanager.ReactShadowNode;
import com.facebook.react.uimanager.UIImplementation;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.uimanager.ViewManagerRegistry;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.image.ReactImageManager;

/**
 * FlatUIImplementation builds on top of UIImplementation and allows pre-creating everything
 * required for drawing (DrawCommands) and touching (NodeRegions) views in background thread
 * for faster drawing and interactions.
 */
public class FlatUIImplementation extends UIImplementation {

  public static FlatUIImplementation createInstance(
      ReactApplicationContext reactContext,
      List<ViewManager> viewManagers) {

    ReactImageManager reactImageManager = findReactImageManager(viewManagers);
    if (reactImageManager != null) {
      Object callerContext = reactImageManager.getCallerContext();
      if (callerContext != null) {
        RCTImageView.setCallerContext(callerContext);
      }
    }
    DraweeRequestHelper.setResources(reactContext.getResources());

    TypefaceCache.setAssetManager(reactContext.getAssets());

    viewManagers = new ArrayList<>(viewManagers);
    viewManagers.add(new RCTViewManager());
    viewManagers.add(new RCTTextManager());
    viewManagers.add(new RCTRawTextManager());
    viewManagers.add(new RCTVirtualTextManager());
    viewManagers.add(new RCTTextInlineImageManager());
    viewManagers.add(new RCTImageViewManager());
    viewManagers.add(new RCTTextInputManager());

    ViewManagerRegistry viewManagerRegistry = new ViewManagerRegistry(viewManagers);
    FlatNativeViewHierarchyManager nativeViewHierarchyManager = new FlatNativeViewHierarchyManager(
        viewManagerRegistry);
    FlatUIViewOperationQueue operationsQueue = new FlatUIViewOperationQueue(
        reactContext,
        nativeViewHierarchyManager);
    return new FlatUIImplementation(reactImageManager, viewManagerRegistry, operationsQueue);
  }

  /**
   * Helper class that sorts moveTo/moveFrom arrays passed to #manageChildren().
   * Not used outside of the said method.
   */ 
  private final MoveProxy mMoveProxy = new MoveProxy();
  private final StateBuilder mStateBuilder;
  private @Nullable ReactImageManager mReactImageManager;

  private FlatUIImplementation(
      @Nullable ReactImageManager reactImageManager,
      ViewManagerRegistry viewManagers,
      FlatUIViewOperationQueue operationsQueue) {
    super(viewManagers, operationsQueue);
    mStateBuilder = new StateBuilder(operationsQueue);
    mReactImageManager = reactImageManager;
  }

  @Override
  protected ReactShadowNode createRootShadowNode() {
    if (mReactImageManager != null) {
      // This is not the best place to initialize DraweeRequestHelper, but order of module
      // initialization is undefined, and this is pretty much the earliest when we are guarantied
      // that Fresco is initalized and DraweeControllerBuilder can be queried. This also happens
      // relatively rarely to have any performance considerations.
      DraweeRequestHelper.setDraweeControllerBuilder(
          mReactImageManager.getDraweeControllerBuilder());
      mReactImageManager = null;
    }

    return new FlatRootShadowNode();
  }

  @Override
  protected ReactShadowNode createShadowNode(String className) {
    ReactShadowNode cssNode = super.createShadowNode(className);
    if (cssNode instanceof FlatShadowNode || cssNode.isVirtual()) {
      return cssNode;
    }

    ViewManager viewManager = resolveViewManager(className);
    return new NativeViewWrapper(viewManager);
  }

  @Override
  protected void handleCreateView(
      ReactShadowNode cssNode,
      int rootViewTag,
      @Nullable ReactStylesDiffMap styles) {
    if (cssNode instanceof FlatShadowNode) {
      FlatShadowNode node = (FlatShadowNode) cssNode;

      if (styles != null) {
        node.handleUpdateProperties(styles);
      }

      if (node.mountsToView()) {
        mStateBuilder.enqueueCreateOrUpdateView(node, styles);
      }
    } else {
      super.handleCreateView(cssNode, rootViewTag, styles);
    }
  }

  @Override
  protected void handleUpdateView(
      ReactShadowNode cssNode,
      String className,
      ReactStylesDiffMap styles) {
    if (cssNode instanceof FlatShadowNode) {
      FlatShadowNode node = (FlatShadowNode) cssNode;

      node.handleUpdateProperties(styles);

      if (node.mountsToView()) {
        mStateBuilder.enqueueCreateOrUpdateView(node, styles);
      }
    } else {
      super.handleUpdateView(cssNode, className, styles);
    }
  }

  @Override
  public void manageChildren(
      int viewTag,
      @Nullable ReadableArray moveFrom,
      @Nullable ReadableArray moveTo,
      @Nullable ReadableArray addChildTags,
      @Nullable ReadableArray addAtIndices,
      @Nullable ReadableArray removeFrom) {

    ReactShadowNode parentNode = resolveShadowNode(viewTag);

    // moveFrom and removeFrom are defined in original order before any mutations.
    removeChildren(parentNode, moveFrom, moveTo, removeFrom);

    // moveTo and addAtIndices are defined in final order after all the mutations applied.
    addChildren(parentNode, addChildTags, addAtIndices);
  }

  @Override
  public void setChildren(
      int viewTag,
      ReadableArray children) {

    ReactShadowNode parentNode = resolveShadowNode(viewTag);

    for (int i = 0; i < children.size(); i++) {
      ReactShadowNode addToChild = resolveShadowNode(children.getInt(i));
      addChildAt(parentNode, addToChild, i, i - 1);
    }
  }

  @Override
  public void measure(int reactTag, Callback callback) {
    FlatShadowNode node = (FlatShadowNode) resolveShadowNode(reactTag);
    if (node.mountsToView()) {
      mStateBuilder.ensureBackingViewIsCreated(node);
      super.measure(reactTag, callback);
      return;
    }

    float width = node.getLayoutWidth();
    float height = node.getLayoutHeight();

    float xInParent = node.getLayoutX();
    float yInParent = node.getLayoutY();

    while (true) {
      node =  Assertions.assumeNotNull((FlatShadowNode) node.getParent());
      if (node.mountsToView()) {
        mStateBuilder.ensureBackingViewIsCreated(node);
        break;
      }

      xInParent += node.getLayoutX();
      yInParent += node.getLayoutY();
    }

    float parentWidth = node.getLayoutWidth();
    float parentHeight = node.getLayoutHeight();

    FlatUIViewOperationQueue operationsQueue = mStateBuilder.getOperationsQueue();
    operationsQueue.enqueueMeasureVirtualView(
        node.getReactTag(),
        xInParent / parentWidth,
        yInParent / parentHeight,
        width / parentWidth,
        height / parentHeight,
        callback);
  }

  private boolean ensureMountsToViewAndBackingViewIsCreated(int reactTag) {
    FlatShadowNode node = (FlatShadowNode) resolveShadowNode(reactTag);
    boolean didUpdate = !node.mountsToView();
    node.forceMountToView();
    didUpdate = didUpdate || mStateBuilder.ensureBackingViewIsCreated(node);
    return didUpdate;
  }

  @Override
  public void findSubviewIn(int reactTag, float targetX, float targetY, Callback callback) {
    ensureMountsToViewAndBackingViewIsCreated(reactTag);
    super.findSubviewIn(reactTag, targetX, targetY, callback);
  }

  @Override
  public void measureInWindow(int reactTag, Callback callback) {
    ensureMountsToViewAndBackingViewIsCreated(reactTag);
    super.measureInWindow(reactTag, callback);
  }

  @Override
  public void addAnimation(int reactTag, int animationID, Callback onSuccess) {
    ensureMountsToViewAndBackingViewIsCreated(reactTag);
    super.addAnimation(reactTag, animationID, onSuccess);
  }

  @Override
  public void dispatchViewManagerCommand(int reactTag, int commandId, ReadableArray commandArgs) {
    if (ensureMountsToViewAndBackingViewIsCreated(reactTag)) {
      // need to make sure any ui operations (UpdateViewGroup, for example, etc) have already
      // happened before we actually dispatch the view manager command (since otherwise, the command
      // may go to an empty shell parent without its children, which is against the specs). note
      // that we only want to applyUpdates if the view has not yet been created so that it does
      // get created (otherwise, we may end up changing the View's position when we're not supposed
      // to, for example).
      mStateBuilder.applyUpdates((FlatShadowNode) resolveShadowNode(reactTag));
    }
    super.dispatchViewManagerCommand(reactTag, commandId, commandArgs);
  }

  @Override
  public void showPopupMenu(int reactTag, ReadableArray items, Callback error, Callback success) {
    ensureMountsToViewAndBackingViewIsCreated(reactTag);
    super.showPopupMenu(reactTag, items, error, success);
  }

  @Override
  public void sendAccessibilityEvent(int reactTag, int eventType) {
    ensureMountsToViewAndBackingViewIsCreated(reactTag);
    super.sendAccessibilityEvent(reactTag, eventType);
  }

  /**
   * Removes all children defined by moveFrom and removeFrom from a given parent,
   * preparing elements in moveFrom to be re-added at proper index.
   */
  private void removeChildren(
      ReactShadowNode parentNode,
      @Nullable ReadableArray moveFrom,
      @Nullable ReadableArray moveTo,
      @Nullable ReadableArray removeFrom) {

    int prevIndex = Integer.MAX_VALUE;

    mMoveProxy.setup(moveFrom, moveTo);

    int moveFromIndex = mMoveProxy.size() - 1;
    int moveFromChildIndex = (moveFromIndex == -1) ? -1 : mMoveProxy.getMoveFrom(moveFromIndex);

    int numToRemove = removeFrom == null ? 0 : removeFrom.size();
    int[] indicesToRemove = new int[numToRemove];
    if (numToRemove > 0) {
      Assertions.assertNotNull(removeFrom);
      for (int i = 0; i < numToRemove; i++) {
        int indexToRemove = removeFrom.getInt(i);
        indicesToRemove[i] = indexToRemove;
      }
    }

    // this isn't guaranteed to be sorted actually
    Arrays.sort(indicesToRemove);

    int removeFromIndex;
    int removeFromChildIndex;
    if (removeFrom == null) {
      removeFromIndex = -1;
      removeFromChildIndex = -1;
    } else {
      removeFromIndex = indicesToRemove.length - 1;
      removeFromChildIndex = indicesToRemove[removeFromIndex];
    }

    // both moveFrom and removeFrom are already sorted, but combined order is not sorted. Use
    // a merge step from mergesort to walk over both arrays and extract elements in sorted order.

    while (true) {
      if (moveFromChildIndex > removeFromChildIndex) {
        moveChild(removeChildAt(parentNode, moveFromChildIndex, prevIndex), moveFromIndex);
        prevIndex = moveFromChildIndex;

        --moveFromIndex;
        moveFromChildIndex = (moveFromIndex == -1) ? -1 : mMoveProxy.getMoveFrom(moveFromIndex);
      } else if (removeFromChildIndex > moveFromChildIndex) {
        removeChild(removeChildAt(parentNode, removeFromChildIndex, prevIndex));
        prevIndex = removeFromChildIndex;

        --removeFromIndex;
        removeFromChildIndex = (removeFromIndex == -1) ? -1 : indicesToRemove[removeFromIndex];
      } else {
        // moveFromChildIndex == removeFromChildIndex can only be if both are equal to -1
        // which means that we exhausted both arrays, and all children are removed.
        break;
      }
    }
  }

  /**
   * Unregisters given element and all of its children from ShadowNodeRegistry,
   * and drops all Views used by it and its children.
   */
  private void removeChild(ReactShadowNode child) {
    if (child instanceof FlatShadowNode) {
      FlatShadowNode node = (FlatShadowNode) child;
      if (node.mountsToView() && node.isBackingViewCreated()) {
        // this will recursively drop all subviews
        mStateBuilder.dropView(node);
        removeShadowNode(node);
        return;
      }
    }

    for (int i = 0, childCount = child.getChildCount(); i != childCount; ++i) {
      removeChild(child.getChildAt(i));
    }

    removeShadowNode(child);
  }

  /**
   * Prepares a given element to be moved to a new position.
   */
  private void moveChild(ReactShadowNode child, int moveFromIndex) {
    mMoveProxy.setChildMoveFrom(moveFromIndex, child);
  }

  /**
   * Adds all children from addChildTags and moveFrom/moveTo.
   */
  private void addChildren(
      ReactShadowNode parentNode,
      @Nullable ReadableArray addChildTags,
      @Nullable ReadableArray addAtIndices) {

    int prevIndex = -1;

    int moveToIndex;
    int moveToChildIndex;
    if (mMoveProxy.size() == 0) {
      moveToIndex = Integer.MAX_VALUE;
      moveToChildIndex = Integer.MAX_VALUE;
    } else {
      moveToIndex = 0;
      moveToChildIndex = mMoveProxy.getMoveTo(0);
    }

    int numNodesToAdd;
    int addToIndex;
    int addToChildIndex;
    if (addAtIndices == null) {
      numNodesToAdd = 0;
      addToIndex = Integer.MAX_VALUE;
      addToChildIndex = Integer.MAX_VALUE;
    } else {
      numNodesToAdd = addAtIndices.size();
      addToIndex = 0;
      addToChildIndex = addAtIndices.getInt(0);
    }

    // both mMoveProxy and addChildTags are already sorted, but combined order is not sorted. Use
    // a merge step from mergesort to walk over both arrays and extract elements in sorted order.

    while (true) {
      if (addToChildIndex < moveToChildIndex) {
        ReactShadowNode addToChild = resolveShadowNode(addChildTags.getInt(addToIndex));
        addChildAt(parentNode, addToChild, addToChildIndex, prevIndex);
        prevIndex = addToChildIndex;

        ++addToIndex;
        if (addToIndex == numNodesToAdd) {
          addToChildIndex = Integer.MAX_VALUE;  
        } else {
          addToChildIndex = addAtIndices.getInt(addToIndex);
        }
      } else if (moveToChildIndex < addToChildIndex) {
        ReactShadowNode moveToChild = mMoveProxy.getChildMoveTo(moveToIndex);
        addChildAt(parentNode, moveToChild, moveToChildIndex, prevIndex);
        prevIndex = moveToChildIndex;

        ++moveToIndex;
        if (moveToIndex == mMoveProxy.size()) {
          moveToChildIndex = Integer.MAX_VALUE;
        } else {
          moveToChildIndex = mMoveProxy.getMoveTo(moveToIndex);
        }
      } else {
        // moveToChildIndex == addToChildIndex can only be if both are equal to Integer.MAX_VALUE
        // which means that we exhausted both arrays, and all children are added.
        break;
      }
    }
  }

  /**
   * Removes a child from parent, verifying that we are removing in descending order.
   */
  private static ReactShadowNode removeChildAt(
      ReactShadowNode parentNode,
      int index,
      int prevIndex) {
    if (index >= prevIndex) {
      throw new RuntimeException(
          "Invariant failure, needs sorting! " + index + " >= " + prevIndex);
    }

    return parentNode.removeChildAt(index);
  }

  /**
   * Adds a child to parent, verifying that we are adding in ascending order.
   */
  private static void addChildAt(
      ReactShadowNode parentNode,
      ReactShadowNode childNode,
      int index,
      int prevIndex) {
    if (index <= prevIndex) {
      throw new RuntimeException(
          "Invariant failure, needs sorting! " + index + " <= " + prevIndex);
    }

    parentNode.addChildAt(childNode, index);
  }

  @Override
  protected void updateViewHierarchy(EventDispatcher eventDispatcher) {
    mStateBuilder.beforeUpdateViewHierarchy();
    super.updateViewHierarchy(eventDispatcher);
    mStateBuilder.afterUpdateViewHierarchy(eventDispatcher);
  }

  @Override
  protected void applyUpdatesRecursive(
      ReactShadowNode cssNode,
      float absoluteX,
      float absoluteY,
      EventDispatcher eventDispatcher) {
    mStateBuilder.applyUpdates((FlatRootShadowNode) cssNode);
  }

  @Override
  public void removeRootView(int rootViewTag) {
    mStateBuilder.removeRootView(rootViewTag);
    super.removeRootView(rootViewTag);
  }

  @Override
  public void setJSResponder(int possiblyVirtualReactTag, boolean blockNativeResponder) {
    ReactShadowNode node = resolveShadowNode(possiblyVirtualReactTag);
    while (node.isVirtual()) {
      node = node.getParent();
    }
    int tag = node.getReactTag();

    // if the node in question doesn't mount to a View, find the first parent that does mount to
    // a View. without this, we'll crash when we try to set the JSResponder, since part of that
    // is to find the parent view and ask it to not intercept touch events.
    while (node instanceof FlatShadowNode && !((FlatShadowNode) node).mountsToView()) {
      node = node.getParent();
    }

    FlatUIViewOperationQueue operationsQueue = mStateBuilder.getOperationsQueue();
    operationsQueue.enqueueSetJSResponder(
        node == null ? tag : node.getReactTag(),
        possiblyVirtualReactTag,
        blockNativeResponder);
  }

  private static @Nullable ReactImageManager findReactImageManager(List<ViewManager> viewManagers) {
    for (int i = 0, size = viewManagers.size(); i != size; ++i) {
      if (viewManagers.get(i) instanceof ReactImageManager) {
        return (ReactImageManager) viewManagers.get(i);
      }
    }

    return null;
  }
}
