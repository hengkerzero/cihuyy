## 2026-05-13 - Android UI Accessibility Polish
**Learning:** Decorative icons in Android XML layouts (like app logos) need `android:importantForAccessibility="no"` to prevent screen readers from reading "Unlabelled image". `EditText` fields also need `android:hint` or `android:labelFor` to pass lint checks and provide proper screen reader context.
**Action:** Always verify Android layout XML with `./gradlew lint` to catch these missing accessibility properties early. Check that decorative images are properly marked.
