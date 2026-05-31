## 2024-05-18 - Icon-Only Button Discoverability
**Learning:** For icon-only Material buttons in Android (minSdk 27), setting `android:contentDescription` improves screen reader accessibility, but does not provide visual cues to sighted users on long-press or hover.
**Action:** Always map `app:tooltipText` to the same string resource as `android:contentDescription` on icon-only `MaterialButton` components to ensure comprehensive backwards-compatible visual tooltips for all users.
