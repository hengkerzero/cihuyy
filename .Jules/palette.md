## 2024-05-29 - Icon-only button tooltips
**Learning:** Adding `android:contentDescription` to icon-only buttons improves accessibility for screen reader users, but sighted users still lack context.
**Action:** Always add `app:tooltipText` matching the `android:contentDescription` for icon-only buttons (`MaterialButton` or similar) to ensure tooltips appear on long-press (or mouse hover) to aid discoverability for all users.
