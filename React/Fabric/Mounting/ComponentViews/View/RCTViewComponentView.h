/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#import <UIKit/UIKit.h>

#import <React/RCTComponentViewProtocol.h>
#import <React/UIView+ComponentViewProtocol.h>
#import <fabric/core/EventEmitter.h>
#import <fabric/core/LayoutMetrics.h>
#import <fabric/core/Props.h>
#import <fabric/view/ViewEventEmitter.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * UIView class for <View> component.
 */
@interface RCTViewComponentView : UIView <RCTComponentViewProtocol> {
@protected
  facebook::react::LayoutMetrics _layoutMetrics;
  facebook::react::SharedProps _props;
  facebook::react::SharedViewEventEmitter _eventEmitter;
}

/**
 * Represents the `UIView` instance that is being automatically attached to
 * the component view and laid out using on `layoutMetrics` (especially `size`
 * and `padding`) of the component.
 * This view must not be a component view; it's just a convenient way
 * to embed/bridge pure native views as component views.
 * Defaults to `nil`. Assing `nil` to remove view as subview.
 */
@property (nonatomic, strong, nullable) UIView *contentView;

@end

NS_ASSUME_NONNULL_END
