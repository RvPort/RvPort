# RvPort frontend UI direction

English | [简体中文](AGENTS.md)

## Product positioning

RvPort is a migration portal for individual users, not an enterprise administration console. Ant Design supplies a stable interaction foundation, but does not dictate the visual character of pages.

## Default visual rules

- Establish hierarchy through whitespace, font size, weight, color, and content order.
- Do not decorate with borders, dividers, or grids of boxes. Lines represent real boundaries, progress, workflow relationships, focus, and necessary state feedback.
- Prefer subtle backgrounds and spatial grouping. Reserve shadows for floating elements and overlays that genuinely have depth.
- List cards should tell a continuous story: what the project is, its current stage, conclusions or blockers, and the user's next action.
- Do not split business conclusions into dashboard-style metric grids. Prefer natural-language summaries and emphasize only meaningful exceptions.
- Keep one clear primary action per area. Reduce secondary-action emphasis instead of competing with many outlined buttons.
- Use primary blue only for key actions and active progress. Apply success, warning, and risk colors sparingly according to meaning.
- Preserve three desktop widths, keyboard focus, readable contrast, and `prefers-reduced-motion`. Do not add mobile-only layouts.

When adding or changing a page, check that it helps users continue their task rather than presenting another table to fill in and manage.

## Code structure

- Follow [style guide](docs/STYLE_GUIDE.md) for typography and spacing. Theme values live only in `src/theme.ts`. Page and section headings use semantic mixins in `src/styles/mixins.scss`, retaining secondary internal hierarchy.
- Page entry points compose views. Put data requests, state transitions, and complex presentation into `use*.ts`, `queryKeys.ts`/`queries.ts`, `presentation.ts(x)`, and `components/` respectively.
- Do not import another business stage's internal components, utilities, or CSS Modules. Promote cross-stage reuse to the nearest shared `components/` or `utils/` first.
- Import concrete API modules directly rather than whole-domain aggregation barrels.
- Generate React Query keys through domain key factories. Subscribe to the same data only once within a view tree.
- Split domain types by business module and import them directly. Do not retain compatibility re-export entry points. Each business concept has one authoritative type definition.
- Aim for roughly 300 lines per page entry point and 250 per Hook. Split by responsibility rather than mechanically by line count.
- Keep tests near domain implementations. Split large contract suites by API module rather than making one all-encompassing file.
