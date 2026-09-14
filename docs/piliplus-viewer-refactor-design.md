# PiliPlus-style Viewer Refactor Design

## Reference architecture

- PiliPlus opens a dedicated gallery/viewer page instead of animating and
  coordinating gestures inside the originating grid container.
- Its item pages own scroll and zoom state, so the pager does not steal
  long-image drags while the page content is scrollable.
- In this first standalone viewer cut, the PhotoView page owns its gestures and
  no custom parent dispatcher intercepts them. This removes the prior
  click/pinch/scroll conflicts.

## c001apk adaptation

- Keep `ImageUtil.startBigImgView` as the stable entry point.
- Route all `ImageUtil` viewer entry points through a standalone viewer activity.
- Use AndroidX `ViewPager2` for paging and PhotoView for each page's zoom,
  double-tap, pan, and pinch gestures.
- Keep the existing save/share/copy helper implementation in `ImageUtil`; the
  standalone viewer does not yet wire a long-press callback, so that behavior
  is not available in this initial cut.
- Preserve the thumbnail→original URL mapping in the new entry points.
- Keep the long-image behavior in PhotoView's own pan logic; do not reintroduce
  a parent touch dispatcher.
- Retain the Mojito modules so unrelated compilation entry points remain valid,
  but stop using the Mojito gesture container for image viewing.
