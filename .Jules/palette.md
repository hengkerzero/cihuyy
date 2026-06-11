## 2026-06-11 - Add tooltips to icon-only buttons
**Learning:** For Android layouts, standard Material buttons and icon-only ImageViews with a `minSdk` >= 26 can greatly benefit from `android:tooltipText` to improve accessibility and discoverability. It acts effectively alongside `android:contentDescription`.
**Action:** Always add `android:tooltipText` to icon-only buttons and interactive ImageViews, mirroring their `android:contentDescription` to provide explicit visual hints for users on long-press or hover.
