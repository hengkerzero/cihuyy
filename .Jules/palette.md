## 2024-05-18 - Added Tooltips to Icon-Only Buttons
**Learning:** Native `android:tooltipText` attribute can be safely added to XML layouts directly for API 26+, leveraging existing `contentDescription` strings to provide helpful text on hover/long-press without additional dependencies or wrapper classes.
**Action:** Always check `minSdk` before adding accessibility wrappers; if >= 26, prefer native XML attributes for tooltips on icon-only buttons.
