## 2026-06-01 - [Tooltip Accessibility for Material Components]
**Learning:** When adding tooltips to Material components (like icon-only buttons) in Android, using `app:tooltipText` instead of `android:tooltipText` ensures comprehensive backwards compatibility, mapping it to the same string resource as `android:contentDescription` to improve discoverability.
**Action:** Always use `app:tooltipText` in XML layouts for Material components to maintain consistent accessibility support across different API levels.
