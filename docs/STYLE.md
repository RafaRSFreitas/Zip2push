# JornalWeb Design System — Style Reference

Extracted from **JornalWeb v1.4.7** (the reference APK). Use this file as the single source of truth for new projects instead of attaching the APK again.

- **Stack origin**: Next.js + Tailwind CSS v4 + shadcn/ui, wrapped in Capacitor for Android
- **Color system**: shadcn/ui **zinc** neutral palette, defined in `oklch`
- **Typography**: **Geist** (sans) and **Geist Mono** — variable fonts, weight axis 100–900
- **Dark mode**: class strategy (`.dark` on `<html>`); Android equivalent: `values-night/` resource qualifiers + `DayNight` theme
- **Base radius**: `--radius: 0.625rem` (= **10dp** on Android)
- **Language**: pt-BR (app content), UI copy can be localized

---

## 1. Color tokens

All values below are the exact oklch originals from the compiled CSS, with their sRGB hex equivalents (use hex on Android XML).

### Light (day)

| Token | oklch | Hex |
| --- | --- | --- |
| `background` | `oklch(100% 0 0)` | `#FFFFFF` |
| `foreground` | `oklch(14.5% 0 0)` | `#0A0A0A` |
| `card` | `oklch(100% 0 0)` | `#FFFFFF` |
| `card_foreground` | `oklch(14.5% 0 0)` | `#0A0A0A` |
| `popover` | `oklch(100% 0 0)` | `#FFFFFF` |
| `popover_foreground` | `oklch(14.5% 0 0)` | `#0A0A0A` |
| `primary` | `oklch(20.5% 0 0)` | `#171717` |
| `primary_foreground` | `oklch(98.5% 0 0)` | `#FAFAFA` |
| `secondary` | `oklch(97% 0 0)` | `#F5F5F5` |
| `secondary_foreground` | `oklch(20.5% 0 0)` | `#171717` |
| `muted` | `oklch(97% 0 0)` | `#F5F5F5` |
| `muted_foreground` | `oklch(55.6% 0 0)` | `#737373` |
| `accent` | `oklch(97% 0 0)` | `#F5F5F5` |
| `accent_foreground` | `oklch(20.5% 0 0)` | `#171717` |
| `destructive` | `oklch(57.7% .245 27.325)` | `#E7000B` |
| `border` | `oklch(92.2% 0 0)` | `#E5E5E5` |
| `input` | `oklch(92.2% 0 0)` | `#E5E5E5` |
| `ring` | `oklch(70.8% 0 0)` | `#A1A1A1` |

### Dark (night)

| Token | oklch | Hex |
| --- | --- | --- |
| `background` | `oklch(14.5% 0 0)` | `#0A0A0A` |
| `foreground` | `oklch(98.5% 0 0)` | `#FAFAFA` |
| `card` | `oklch(20.5% 0 0)` | `#171717` |
| `card_foreground` | `oklch(98.5% 0 0)` | `#FAFAFA` |
| `popover` | `oklch(20.5% 0 0)` | `#171717` |
| `popover_foreground` | `oklch(98.5% 0 0)` | `FAFAFA` |
| `primary` | `oklch(92.2% 0 0)` | `#E5E5E5` |
| `primary_foreground` | `oklch(20.5% 0 0)` | `#171717` |
| `secondary` | `oklch(26.9% 0 0)` | `#262626` |
| `secondary_foreground` | `oklch(98.5% 0 0)` | `#FAFAFA` |
| `muted` | `oklch(26.9% 0 0)` | `#262626` |
| `muted_foreground` | `oklch(70.8% 0 0)` | `#A1A1A1` |
| `accent` | `oklch(26.9% 0 0)` | `#262626` |
| `accent_foreground` | `oklch(98.5% 0 0)` | `#FAFAFA` |
| `destructive` | `oklch(70.4% .191 22.216)` | `#FF6467` |
| `border` | `oklch(100% 0 0/.1)` | `#1AFFFFFF` (10% white) |
| `input` | `oklch(100% 0 0/.15)` | `#26FFFFFF` (15% white) |
| `ring` | `oklch(55.6% 0 0)` | `#737373` |

### Semantic extras (zinc/red/green family used by the web app)

| Purpose | Light | Dark |
| --- | --- | --- |
| Success | `#16A34A` (green-600) | `#4ADE80` (green-400) |
| Error / destructive text | `#DC2626` (red-600) | `#FF6467` |
| Link | zinc-900 `#18181B` | zinc-100 `#F4F4F5` |
| Badge neutral bg/text | `#F5F5F5` / `#3F3F46` | `#262626` / `#D4D4D8` |
| Badge red bg/text | `#FEF2F2` / `#B91C1C` | `#3F1D1D` / `#FCA5A5` |

---

## 2. Typography

**Family**: Geist (body/headings), Geist Mono (code/paths). Variable weight **100–900**, default 400. Both are SIL OFL licensed and free to bundle.

| Scale (Tailwind) | Size | Line height | Weight usage |
| --- | --- | --- | --- |
| `text-xs` | 12sp | 1 / .75 = 1.33 | 12sp/16 line-height |
| `text-sm` | 14sp | 1.43 | body default (most used) |
| `text-base` | 16sp | 1.5 | secondary body |
| `text-lg` | 18sp | 1.56 | emphasized body |
| `text-xl` | 20sp | 1.4 | card/page headings |
| tracking-tight | -0.025em | — | headings |
| tracking-widest | 0.1em | — | uppercase labels |
| font-medium / semibold / bold | 500 / 600 / 700 | — | weights |

Android mapping (ZipPush implementation):
- HeadlineSmall = 20sp bold, letterSpacing −0.025
- TitleMedium = 16sp bold, letterSpacing −0.025
- BodyMedium = 14sp normal
- BodySmall = 12sp normal
- LabelLarge = 14sp bold (buttons)
- LabelMedium = 12sp bold (badges)

**Font files**: `res/font/geist_variable.ttf`, `res/font/geist_mono_variable.ttf` (in ZipPush; re-export from the JornalWeb APK or GitHub - vercel/geist-font if needed).

---

## 3. Shape, spacing, elevation

| Element | Value |
| --- | --- |
| Base corner radius | **10dp** (`--radius .625rem`) |
| Small radius (chips, inputs) | 8dp (rounded-md 0.5rem) |
| Large radius (dialogs, popovers) | 12–14dp (rounded-xl) |
| Pill/full radius | 50% (badges can be pill) |
| Card padding | 16dp |
| Screen padding | 16dp |
| Spacing unit | 4dp (`--spacing .25rem`) |
| Elevation | **0** — flat design, borders instead of shadows |

Visual style is **flat with 1dp borders** (shadcn): cards and popovers get a 1dp `border` stroke and no shadow.

---

## 4. Components (shadcn mapping)

| Web component | Android equivalent |
| --- | --- |
| Card | `MaterialCardView`, `cardBackgroundColor=card`, `strokeWidth=1dp` `strokeColor=border`, `cardElevation=0`, radius 10dp |
| Button primary | Filled `MaterialButton`, bg `primary`, text `primary_foreground` |
| Button secondary | Tonal button, bg `secondary`, text `secondary_foreground` |
| Button outline | Outlined button, transparent bg, 1dp `border` stroke, text `foreground` |
| Button ghost | Text button, text `foreground` |
| Input | `TextInputLayout` OutlinedBox, see "Form fields" below |
| Dialog / popover | `MaterialAlertDialog`, surface `popover`, radius 10dp |
| Dropdown menu | `PopupMenu` / exposed dropdown with `popover` bg + 1dp border |
| Badge | TextView, pill or 6–8dp radius, zinc (neutral) or red (private/destructive) |
| Toast/status | Status bar strip, bg `secondary`/`muted`, text `muted_foreground` |

### Form fields (critical for dark-mode visibility)

JornalWeb's inputs (shadcn `Input` component, e.g. the "adicionar jornal" form)
are **filled, not bare outlines**:

- **Light**: near-white fill `#FAFAFA`, border `#E5E5E5`, focus border zinc-500 `#71717A`.
- **Dark**: `dark:bg-input/30` + `dark:border-input` — the `input` token is 15% white, mixed 30% over the card. Over `#171717` this yields a **fill of `#212121`**, stroke ≈ `#2E2E2E`–`#383838` (focus zinc-600 `#525252`), placeholder `#A1A1A1`, text `#FAFAFA`.

Android implementation: `Widget.ZipPush.TextInputLayout` sets
`boxBackgroundColor=@color/jw_input_fill`, `boxStrokeColor=@color/input_stroke`
(state list: `jw_input_border` at rest, `jw_input_border_focus` focused),
`hintTextColor=@color/jw_input_hint`. Verified contrast (WCAG): dark text on
dark fill 15.4:1 (AA ✓), placeholder 6.2:1 (AA ✓).

**UX rule (why outline-only dark inputs fail)**: in dark mode, fields with
only a low-alpha border (10–15% white) become nearly invisible on a dark
card. WCAG 1.4.11 asks ≥3:1 for component boundaries; JornalWeb solves this
with a solid *fill* (`bg-input/30`) rather than a stronger outline alone.
When porting, always fill dark inputs.

### Day/night mode toggle

- Top-right header, left of the 3-dot overflow menu: an icon button.
- Icon shows the mode you would switch TO (moon in day, sun in night).
- Default: follow system (`MODE_NIGHT_FOLLOW_SYSTEM`); a manual tap persists
  the override (`MODE_NIGHT_NO`/`MODE_NIGHT_YES`) in preferences.

---

## 5. Dark mode strategy

- Web: `next-themes` class strategy — `.dark` class on `<html>`, persisted in localStorage, default follows system (`prefers-color-scheme`).
- Android: `Theme.Material3.DayNight.NoActionBar` parent + `values/` (light) and `values-night/` (dark) color resources. System dark mode toggling is automatic.
- Dark overlays: borders/inputs in dark mode are **alpha white** (`#1AFFFFFF` 10%, `#26FFFFFF` 15%), not solid colors.
- Status bar / navigation bar match `background`; `windowLightStatusBar` true in light, false in dark.

---

## 6. Where this lives in ZipPush (v0.2.0+)

- `app/src/main/res/values/colors_jw.xml` — light tokens
- `app/src/main/res/values-night/colors_jw.xml` — dark tokens
- `app/src/main/res/values/themes.xml` — DayNight theme + component styles
- `app/src/main/res/values-night/themes.xml` — night system-bar overrides
- `app/src/main/res/font/` — Geist variable TTFs
- `app/src/main/res/drawable/bg_popup.xml` — popover background (border + 10dp corners)

To reuse in a new project: copy those six locations, point the theme's parent at DayNight, done.
