## 2026-05-26 - Adding Tooltips to Icon-Only Buttons
**Learning:** Icon-only MaterialButtons require `app:tooltipText` in addition to `android:contentDescription` to improve discoverability on long-press, as users often overlook their purpose without visual feedback. Using `app:tooltipText` ensures backwards compatibility.
**Action:** Always map `app:tooltipText` to the same string resource used for `android:contentDescription` when implementing icon-only buttons in Material design components.
