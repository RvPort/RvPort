# Frontend style guide

English | [简体中文](STYLE_GUIDE.zh-CN.md)

Applies to the project home, administration, personal settings, Workflow/Step content, and shared overlays. Reuse existing components; visual consistency must not change business flows.

## Typography

Define values only in `src/theme.ts`. Components use official Ant Design CSS variables, with semantic mixins from `src/styles/mixins.scss`. Do not introduce a parallel theme-variable system.

| Purpose                                   | Size / weight / line height | Usage                |
| ----------------------------------------- | --------------------------- | -------------------- |
| Main page title                           | 20px / 500 / 28px           | `type.page-title`    |
| Modal, drawer, and Step detail title      | 18px / 500 / 24px           | `type.panel-title`   |
| Content section title                     | 16px / 500 / 24px           | `type.section-title` |
| Body, forms, and ordinary actions         | 14px / 400 / 22px           | `type.body-text`     |
| Hints, labels, times, and status metadata | 12px / 400 / 20px           | `type.helper-text`   |

- Use weights 400, 500, and 600. Reserve 600 for conclusions or values that need emphasis rather than every heading.
- Dense lists, paths, and logs may use 13px / 20px. Secondary source-workspace headings remain 15px / 500 / 22px, and file paths 13px / 400 / 20px. Do not promote internal details to page-title scale.
- Numeric summaries may use 20px or 24px; do not add nearby sizes such as 21px, 22px, or 26px.
- Ordinary page text must not use 10–11px. Workflow canvas labels may use 11px because they scale with the entire canvas. Icon sizes are independent of typography.
- Monaco keeps its editor-managed 14px font. Paths, logs, and code snippets use `--ant-font-family-code`; other text inherits the system font.
- BaseModal and BaseDrawer cover both string titles and title objects; StagePanel uses the same title specification. Custom business titles reuse shared title styles without extra local font-size overrides.

```scss
@use '../../styles/mixins' as type;

.title {
  @include type.page-title;
  color: var(--ant-color-text);
}
```

## Spacing and hierarchy

Use the same spacing for equivalent content instead of near-duplicates such as 20 / 22 / 28px across neighboring pages.

| Relationship                                         | Default spacing |
| ---------------------------------------------------- | --------------- |
| Independent major sections                           | 24px            |
| Groups within a section; adjacent content columns    | 16px            |
| Heading and immediate description                    | 4px             |
| Compact list heading to list; list items             | 12px            |
| Related action buttons or filter controls            | 8px             |
| Execution rows and compact summary/list card padding | 12px 16px       |
| Standalone conclusion/suggestion container padding   | 16px            |

- Page titles normally have 16px before body content. Administration pages with a divider keep 24px in both heading and body areas. Real source/list container boundaries may have padding on both sides.
- Large whitespace may use 32 / 48px; two compact metadata lines may use 2 / 4px. Do not use these compact values between an independent section heading and its body.
- Inline icon spacing, canvas coordinates, editor dimensions, and compact navigation may use purpose-specific values. Do not alter layouts just to round numbers.
- Overlay heading padding is `12px 24px`; actions use `16px 24px`. Modal bodies use 24px; Drawer bodies use `16px 24px`. Embedded StagePanel remains compact.
- Main text uses `--ant-color-text`, descriptions use `--ant-color-text-secondary`, and optional metadata uses `--ant-color-text-tertiary`. Keep existing semantics for risk, running, and success colors.
- Build hierarchy with font size, weight, and whitespace. Prefer subtle container backgrounds. Combined source workspaces retain one real boundary without decorative nested cards.

## Recorded audit and corrections

| Issue                                                                                                          | Correction                                                                     |
| -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| Administration titles at 28px, home/settings at 20px, management center at 24px                                | Standardize page titles to 20px / 500 / 28px                                   |
| BaseDrawer object titles at 20px, BaseModal at 18px / 600, StepPanel at 16px; string titles relied on defaults | Shared containers use 18px / 500 / 24px for both title forms                   |
| Peer stage headings used 600 or browser bold with inconsistent line heights                                    | Shared mixins for 16px section headings, retaining secondary workspace levels  |
| Many status descriptions, versions, and analysis metadata at 10–11px                                           | Raise ordinary text to 12px; retain small icons and canvas labels              |
| Weights 550 / 620 / 650 and near-duplicate numeric sizes                                                       | Standard weights and 20 / 24px numeric summaries                               |
| Repeated hardcoded neutral colors, monospace fonts, and standard font sizes                                    | Existing official Ant Design variables                                         |
| Inconsistent equivalent spacing on home, administration, and overlays                                          | Align headings, lists, and overlays while retaining required layout dimensions |

Additional corrections: assessment report sections changed from 28 / 20px to 24px; optimization stages from 22 / 28px to 24px. Equivalent knowledge-base, dependency-analysis, and report rows changed vertical padding from 13 / 14px to 12px. Build, test, and archive headings use 4px before descriptions; project-creation and stage-action buttons use 8px gaps.

## Containers and state displays

- Ordinary inputs/buttons use 4px radii, small records/notices/internal containers 8px, and main workspaces/overlays/home project cards 12px. Use twice the base radius for 8px and `--ant-border-radius-lg` for 12px. Circular icons, pill labels, and workflow shapes retain purpose-specific dimensions.
- Ordinary groups use `--ant-color-fill-quaternary`; interactive hover uses `--ant-color-fill-tertiary`. Source, logs, and tables retain genuine boundaries. Risk and prerequisites use official semantic colors.
- Content cards use whitespace, backgrounds, and necessary boundaries. Reserve shadows for overlays and genuinely floating layers.
- Use `ContentState` for no data, no filter matches, failed requests, and loading without a known layout. Standalone areas have a minimum height of 240px; embedded areas use `compact` at 160px. Descriptions, graphics, and actions share one layout.
- Failures use `kind="error"`, no filter matches use `kind="filtered"`, and loading uses `kind="loading"`. Keep errors distinct from empty data; callers provide retry callbacks.
- Known-layout initial loading continues to use `StageViewLoading` skeletons. Local requests such as inline AI suggestions use a small Spin. `StageStateNotice` preserves business facts for stage prerequisites, execution progress, and failures, using shared typography and spacing.

## Actions and interaction feedback

- Keep one primary action per area. Back, cancel, and clear-filter actions use default buttons; history and auxiliary navigation use link/text. Destructive actions retain danger styling and existing confirmation flows.
- Page/section request retries use standard primary buttons; remove default button shadows. Existing business logic controls disabled, loading, and busy states.
- Native clickable elements provide a 2px primary-color `:focus-visible` outline with a 2px offset. Use background/color hover feedback rather than card movement.
- Color transitions use `--ant-motion-duration-mid` (200ms); overlays use the corresponding official durations. With `prefers-reduced-motion`, stop repeating decorative animation and shorten transitions while retaining actions and state information.

Local title overrides in the new-project dialog have been completed. Shared state components serve home, administration details, stage results, execution records, logs, and empty reports, and reuse ordinary loading patterns with BaseTable and StageViewLoading.

## Acceptance

- Check the four levels of page, section, list-item, and evidence-workspace information; avoid uniformly large, bold text.
- At desktop widths 1280 / 1440 / 1920, check wrapping, long paths, buttons, scroll containers, and overlays. Pay particular attention to project context bars after auxiliary text is enlarged.
- Retain keyboard focus, contrast, and reduced-motion support. Record visual acceptance separately from formatting, type, test, and build checks.
