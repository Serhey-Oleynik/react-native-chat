/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#include <react/components/art/ARTSurfaceViewState.h>
#include <react/debug/debugStringConvertibleUtils.h>

namespace facebook {
namespace react {

#ifdef ANDROID
folly::dynamic ARTSurfaceViewState::getDynamic() const {
  return folly::dynamic::object();
}
#endif

} // namespace react
} // namespace facebook
