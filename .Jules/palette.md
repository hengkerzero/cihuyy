## 2024-05-24 - Add tooltips to icon-only buttons
**Learning:** For Android layout XML files (minSdk 27+), use `android:tooltipText` on icon-only interactive elements (like `MaterialButton` or `ImageView`) to improve discoverability on hover or long-press. Mirror the string resource used for `android:contentDescription`. Note that `app:tooltipText` is only processed for `MenuItem` components in menu resources.
**Action:** Always include `android:tooltipText` matching `android:contentDescription` when adding or modifying icon-only buttons in XML layouts.
