# RWC Waste Tracking System

Implemented workflows are **normal Crate / Pallet**, **Salvage**, **Pickup**, **Electrical Crate / Pallet**, **Collectables**, **Whitegoods**, **Electrical Testing**, **E-waste Pickup**, and **Scrap**: sign in, enter an operational record, review calculated weights, submit, and view saved records. Admins can correct and soft-delete submitted records. Admin Overview, Reports and a Monthly Waste Levy Summary working aid are available. Admin Settings is available. Admin User Management is available under Settings.

Requirements: [AGENTS.md](AGENTS.md) and the approved [SRS](docs/SRS-%20Waste%20Tracking%20Operations%20System%20for%20Resource%20Work%20Cooperative.pdf), supplemented by the approved workflow decisions and source datasheets in `docs`.

## Local setup

Use JDK 21 or later, Maven 3.9+, Node.js compatible with the existing Vite installation, and PostgreSQL. The commands below use Windows PowerShell and installed Maven. Use `npm.cmd` to avoid PowerShell script execution-policy restrictions.

Create/provide a PostgreSQL database and application role before starting. Defaults are `jdbc:postgresql://localhost:5433/rwc_waste_tracking` and username `rwc_app`; override through `DB_URL` and `DB_USERNAME` when necessary. The role must be able to apply Flyway migrations to its schema.

Set these environment variables in the backend terminal:

| Variable | Purpose |
| --- | --- |
| `DB_PASSWORD` | Required database password, with no source-code default |
| `DB_URL` | Optional JDBC URL override |
| `DB_USERNAME` | Optional database username override |
| `INITIAL_STAFF_USERNAME`, `INITIAL_STAFF_PASSWORD` | Shared Staff account for initial provisioning |
| `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_PASSWORD` | Individual initial Admin account for initial provisioning |
| `SESSION_COOKIE_SECURE` | Set `true` when serving through HTTPS; defaults to `false` for local HTTP |

For example, enter credentials without including passwords in command history:

```powershell
$env:DB_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Database password' -AsSecureString)).Password
$env:INITIAL_STAFF_USERNAME = Read-Host 'Shared Staff username'
$env:INITIAL_STAFF_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Initial Staff password' -AsSecureString)).Password
$env:INITIAL_ADMIN_USERNAME = Read-Host 'Initial Admin username'
$env:INITIAL_ADMIN_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Initial Admin password' -AsSecureString)).Password
Set-Location backend
mvn.cmd spring-boot:run
```

Provisioning hashes passwords with BCrypt. It creates missing accounts and never resets existing passwords. Supply both username and password for a role, or omit both. Existing accounts retain their current role, status and password. Provisioning a second missing initial shared Staff account causes startup to fail; additional accounts are managed through Admin Settings. Remove initial-account password variables from later launches after provisioning. Do not put credentials or hashes into migrations or tracked files. `.env` files are ignored by Git but are not loaded automatically by Spring Boot.

In a second terminal:

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run dev
```

Open the Vite address printed in the terminal. Vite proxies `/api` to the backend on port 8080. `API_PROXY_TARGET` can override that target. Authentication uses an HttpOnly session cookie and session-backed CSRF tokens; no JWT or browser token storage is used.

For deployment, serve the frontend and `/api` from the same origin through HTTPS, proxying API requests to Spring Boot. Set `SESSION_COOKIE_SECURE=true`. The Vite development server is not the production hosting setup.

## Using the workflow

1. Sign in with the shared Staff account or your Admin account.
2. Choose **New Crate / Pallet**. Enter the record date, container, gross weight including the container, and material percentages.
3. Percentages must total exactly **100.00%**. Select **Review record** to calculate the tare, net weight, and material weights on the backend.
4. Use **Back to edit** to correct inputs, then review again. Nothing is stored until **Confirm and submit**.
5. Successful submission opens **Records** with the saved record. Filter by date and select a record number to see its breakdown.
6. Admins can choose **Correct record**, review the correction, and confirm. **Delete record** requires a confirmation and hides the record while retaining database traceability. Staff cannot edit or delete submitted records.

While submitting, navigation, logout, and starting another record are disabled. If a response is lost, use **Retry submission**: it reuses the same UUID and payload. You can also check Records and choose **Resume submission** to return to the unresolved request; its state stays in application memory. Another record cannot replace it while unresolved, and logout is disabled until the outcome is resolved. Reloading or closing the page prompts a browser warning but still discards state if you choose to leave. There are no persistent drafts or browser-storage copies.

A submission-ID/data conflict (HTTP 409) is a definite rejection, not a transport failure: open Records to inspect the existing record instead of repeatedly retrying. Admin corrections also return 409 when their version is stale; reload the record before making another correction. Session-expiration recovery remains outside this change.

If a delete response is lost, retry deletion or refresh Records. A not-found response reconciles the list and clears selected details. Refresh also replaces selected details with the current record or clears them if that record is no longer listed.

If the system is unavailable, use the existing paper forms. An authorized Admin can enter them later using the original operational dates.

## Salvage trips

Choose **New Salvage**. Enter one operational record date and one mandatory crew for the complete trip. Always enter Time Up, Kg Up, Time Down, and Kg Down. These are local operational times; Down may be earlier than or equal to Up. The date must be past or current in Australia/Hobart.

Enter the final Kg Up (waste to landfill) and Kg Down (recovered material) supplied by the council weighbridge. Both weights are recorded independently, with no tare deduction, truck-weight difference calculation, or subtraction of one direction from the other. No digital dockets are stored.

For each direction independently:

- A positive weight requires a material breakdown totaling exactly 100.00%.
- A zero weight still requires its time, hides the percentage inputs, and persists no material rows. Both weights may be zero.
- Changing a positive weight to zero clears its percentages. An Admin correction also removes the old persisted rows. Changing back to positive requires a valid 100% breakdown.
- Category weight is council weight multiplied by percentage divided by 100, rounded individually to two decimals with HALF_UP. Differences are not redistributed.

Select **Review trip**, check both directions and the single crew, then confirm. The same version checks, duplicate-submission protection, in-memory recovery, navigation guards, role permissions, and soft deletion apply. Records has a **Workflow** selector and inclusive From/To date filters. Record identities include the workflow, so equal numeric IDs in the two workflows remain separate.

Material definitions are shared through `material_category`; applicability is restricted by `CRATE_PALLET`, `SALVAGE_UP`, and `SALVAGE_DOWN`. Both the definition and its applicability must be active for a new selection. The backend checks applicability on writes, including direct API submissions. A historical inactive category can be retained during correction in its original direction.

| Salvage Up: waste to landfill (9) | Salvage Down: recovered material (14) |
| --- | --- |
| Iron and Steel; Timber; Textiles; Plastics; Paper, Cardboard and Books; Mattresses; E-waste; Glass; Mixed Materials | Iron and Steel; Aluminium; Other Non-Ferrous Metals; Mixed Metals; E-waste; Lead Acid Batteries; Timber; Bricks, Blocks and Pavers; Building and Demolition Material; Whitegoods / Large Appliances; Paper, Cardboard and Books; Plastics; Glass; Mixed Materials |

E-waste is intentionally available in both Salvage directions. E-waste, Lead Acid Batteries, and Whitegoods / Large Appliances remain unavailable in normal Crate/Pallet.

## Pickup records

Choose **New Pickup**. Enter the operational date, one pickup location (up to 255 characters), the final net material weight, and a material percentage breakdown. Weight must be greater than 0 kg; zero and negative values are rejected. No container, tare, Delivery charge, or pickup-type field is used. Dates must be past or current in Australia/Hobart.

Pickup has its own `PICKUP` applicability context mapped explicitly from [21. Pickup Datasheet.pdf](docs/21.%20Pickup%20Datasheet.pdf). Its thirteen categories reuse the existing definitions: Iron and Steel; Aluminium; Other Non-Ferrous Metals; Mixed Metals; Textiles; Mattresses; Timber; Bricks, Blocks and Pavers; Building and Demolition Material; Paper, Cardboard and Books; Plastics; Glass; Mixed Materials. E-waste, Lead Acid Batteries and Whitegoods / Large Appliances are excluded by both the reference API and write validation.

Percentages must total exactly 100.00%. Material weights use the final net weight directly and are rounded separately with BigDecimal HALF_UP to two places, without redistribution. Review before confirming; final submission recalculates on the backend. Only positive-percentage rows are stored. Staff can create/view; Admins can correct/soft-delete, with version checks and individual account metadata. The existing in-memory retry and navigation protection applies across all three workflows.

## Electrical Crate / Pallet

Choose **New Electrical Crate / Pallet** for separated electrical material. Record the operational date, select an approved container and enter gross weight. Gross weight must be strictly greater than applied tare: zero or negative net material weight is invalid, including gross equal to tare. Dates use Australia/Hobart.

The approved [Electricals Crate and Pallet Datasheet](docs/23.%20Electricals%20Crate%20and%20Pallet%20Datasheet.pdf) lists Stillage Cage (50 kg), Plastic Pallet (6 kg), Pine Pallet (18 kg) and Hardwood Pallet (30 kg). Apple Crate is excluded. Existing container definitions are reused through the separate `ELECTRICAL_CRATE_PALLET` container applicability context; normal Crate/Pallet retains all five of its containers.

Both **TVs / Monitors / Projectors** and **Everything Else** must be supplied exactly once, each from 0 to 100%, and total exactly 100.00%. Only positive-percentage rows are stored, but responses, forms and reviews always include both categories, with zero percentages/weights reconstructed. These electrical categories are not exposed in normal Crate/Pallet, Salvage or Pickup, and generic E-waste is not accepted as a substitute.

Net weight is gross minus the server-selected tare. Tare is stored historically: an Admin correction retains it when the container is unchanged; changing containers uses the newly selected container's current configured tare. Final submissions/corrections recalculate on the backend using BigDecimal, with material weights rounded HALF_UP to two places without redistribution. No donor, source, location, count or separate month field is stored.

The existing Staff/Admin permissions, review-before-submit, UUID retries, version protection, soft deletion and metadata apply. Pending submissions remain in application memory across Records visits. Electrical Testing and dedicated E-waste Pickup are described separately below.

## Collectables

Choose **New Collectables** for one weighed Collectable Tub. The form and review prominently display:

> Only record collectables brought directly from the driveway/outside area. Do not record tubs from the pricing bench or material already counted through another process.

The [Collectable Tub Datasheet](docs/04%20Collectable%20Tub%20Datasheet.pdf) contains date, measured weight and material breakdown. Enter the gross tub weight; the backend applies the fixed **2.00 kg** tare and stores it as historical calculation data. Gross must be strictly greater than 2.00 kg and net must be positive. The tare is not selectable or client-editable. There are no source, customer, location, item-count or eligibility fields, and no automatic cross-workflow material matching.

The independent `COLLECTABLES` applicability context reuses these thirteen category definitions: Iron and Steel; Aluminium; Other Non-Ferrous Metals; Mixed Metals; Textiles; Mattresses; Timber; Bricks, Blocks and Pavers; Building and Demolition Material; Paper, Cardboard and Books; Plastics; Glass; Mixed Materials. The datasheet's "Mixed materials (everything else)" means Mixed Materials, not the electrical Everything Else category. E-waste, batteries, whitegoods and electrical grouping categories are excluded.

Percentages must total exactly 100.00%. Calculations use BigDecimal and HALF_UP to two decimal places without redistribution; only positive-percentage rows are stored. Dates must be past/current in Australia/Hobart. Review before submitting; final submission/correction recalculates on the backend. The saved-record confirmation reminds staff to **mark the physical tub label as recorded**. Staff can create/view, and Admins can correct/soft-delete with version protection and individual account metadata. Existing in-memory retry/navigation safety applies across all five workflows.

## Whitegoods daily entries and monthly summary

Choose **New Whitegoods** and enter only the operational record date and whole-number count from 0 to 2,147,483,647. Zero-count entries are valid, and multiple entries on the same date are allowed. Each entry is a quantity received into RWC, not a manually maintained monthly total. This approved daily-entry model supersedes the SRS's earlier monthly-entry description. No appliance type, source, location or individual-item fields are used.

The backend calculates `count * configured kg per item` (default `48.00 kg`), stores the applied factor historically and saves the estimated weight with the entry. Preview does not save; final submission/correction recalculates and does not trust client factor/weight fields. Counts use PostgreSQL INTEGER. Stored estimated weight uses **NUMERIC(15,2)** so the full integer count range fits even at the maximum configurable `999.99 kg` factor. V12 previously expanded it to NUMERIC(14,2); V14 expands it again without recalculating existing values. Monthly sums use BIGINT/Java Long counts and BigDecimal weights.

The Whitegoods Records view includes a compact **monthly summary** with its own calendar-month selector, initially the current month in Australia/Hobart. It queries active daily entries directly: monthly total count is the sum of those counts, and monthly estimated weight is the sum of stored estimates, preserving months containing different historical factors. The API returns a common `kgPerItem` only when all entries share it, otherwise null (also null for empty months). Empty months return zero. There is no monthly-total table, manually entered monthly total or cached aggregate. The summary is independent of the record list's date filter and reloads when Records opens, refreshes or reconciles deletion. Admin corrections immediately affect subsequent summary queries, including both months when an entry moves dates. Soft-deleted entries are excluded.

Staff create/view entries and summaries. Admins correct/soft-delete with optimistic locking and individual account metadata. The existing review, UUID retry protection and in-memory submission recovery apply; identical data with a new UUID is a valid additional same-day entry. Dates must be past/current in Australia/Hobart. This slice does not implement workbook reporting-section allocation or other reporting/dashboard functions.

## Electrical Testing counters and sales references

Choose **Electrical Testing**. This is internal operational KPI counting only: there are no waste weights, individual electrical item identities, inventory balances or automatic status transitions. Electrical Testing counts do not feed waste reporting or create records in Whitegoods, Collectables or another workflow.

The fifteen dedicated categories follow [Electrical Testing Datasheet.jpg](docs/Electrical%20Testing%20Datasheet.jpg): Kitchen/household; Heaters; Fans/aircon; Lamps; Lighting; iPod docks/clock radios; Stereos and speakers; Surround sound/subwoofers; DVD/VHS/games consoles; TVs/monitors/projectors; Sewing machines; Power tools; Vacuums; Whitegoods; Vintage radios/parts. These are not waste material-category definitions.

Use **Daily Tallies** for the compact fifteen-category grid. Choose **Tally month** and **Tally date**. Counters show the selected month's active accumulated totals, with the selected date's contribution underneath. Plus/minus and direct entry change only that date's contribution in memory; Save Tallies persists the adjustments. Staff can change today only and view history read-only. Admins can change historical dates; future dates are rejected. Minus stops at a zero selected-date contribution and never subtracts from other dates. Reductions apply newest selected-date records first, with version checks; zero soft-deletes a daily record. **Diverted to OLS** and **Diverted to Coll** remain overall non-category counters. Month/date selectors and refresh are disabled while changes are pending. Multiple daily records remain valid, and monthly totals stay derived without a summary table. Pending sessions and partial-save retries remain in memory, retaining original submission IDs and reduction versions. These are independent activity counts, not stock balances or unique items.

A session uses the existing one-record endpoints sequentially, not an atomic batch. Each cell keeps its own UUID/payload. Confirmed cells clear immediately; a failure stops the remaining requests and shows the saved-record progress. Retry skips confirmed cells and reuses the UUID for any uncertain request. Successful completion resets the grid to zero. Pending/uncertain sessions stay in application memory across Records/Operations visits, block sign-out and starting another workflow, and trigger the existing browser-unload guard. An unresolved outcome must be retried; a definitive error permits explicitly clearing the remaining session without changing any saved records. There are no persistent drafts.

The separate **Monthly Sales Reference** tab shows **Sold Last Month** for each category for the selected reporting month. For example, September's reference describes August sales. Use **Enter sales reference** to submit a single category/month value separately from live tallies. Missing references display **Not entered**; an explicit zero means confirmed zero previous-month sales. The application does not turn missing values into zero or copy reference values between months. Only one active reference per reporting month/category is allowed.

Staff create and view tallies/references; submitted references are read-only for Staff. Admins correct or soft-delete both using version protection and account metadata. Reference corrections replace the saved value rather than incrementing it. Deleting a reference restores Not entered, while retaining its historical row. Date/period validation uses Australia/Hobart. The selected tally date and displayed reporting month are labelled separately on the counter screen. Successful creates return to refreshed counters; Admin tally corrections from Records return to the saved detail.

The existing exact-payload/UUID recovery and active-submission navigation protection applies to both forms. A reference unique-month/category conflict is definitive and requires reloading, not an automatic retry. A lost response retains the request for an identical retry. Admin reference deletion has confirmation and reconciles a retry returning 404.

## Data and calculation rules

- Twenty-three application tables (including E-waste Pickup and Scrap): `app_user`, `container_type`, `material_category`, `material_category_applicability`, `crate_pallet_record`, `crate_pallet_material`, `salvage_record`, `salvage_material`, `pickup_record`, `pickup_material`, `container_type_applicability`, `electrical_crate_pallet_record`, `electrical_crate_pallet_material`, `collectable_record`, `collectable_material`, `whitegoods_record`, `electrical_testing_category`, `electrical_testing_tally`, `electrical_testing_sales_reference`, `application_settings`, `ewaste_pickup_record`, `ewaste_pickup_container`, and `scrap_record`.
- Containers: Apple Crate 60 kg, Stillage Cage 50 kg, Plastic Pallet 6 kg, Pine Pallet 18 kg, Hardwood Pallet 30 kg.
- The thirteen normal material categories have independent Crate/Pallet and Pickup mappings. Three additional shared definitions and direction-specific applicability mappings support Salvage.
- Weights use Java `BigDecimal` and PostgreSQL `NUMERIC(10,2)` (Whitegoods estimated weight uses `NUMERIC(15,2)`); percentages use `NUMERIC(5,2)`. Inputs accept at most two decimal places.
- For Crate/Pallet, net weight is gross minus tare; gross cannot be below tare, and zero net weight is allowed. Salvage accepts zero weights independently; Pickup, Electrical Crate/Pallet and Collectables final net weights must be greater than zero.
- Material weight is `net * entered percentage / 100`, rounded independently to two decimals with `HALF_UP`. Rounding differences are not redistributed.
- Dates are checked against the current date in `Australia/Hobart`; future dates are rejected.
- Only positive-percentage breakdown rows are saved. Duplicate categories are rejected.
- Final submission recalculates all values instead of trusting frontend-calculated fields. The record and breakdown are saved atomically.
- `submission_id` is a unique UUID. Matching retries by the same creator return the existing record; reuse with different data, by another account, or after deletion returns HTTP 409.
- Records carry a numeric JPA optimistic-locking `version`. Corrections must submit the version read from the saved record. Stale versions return HTTP 409 without overwriting newer changes; missing correction versions return HTTP 400.
- Creation and latest-update user/timestamps are stored on the record. Deletion sets `deleted_at` and `deleted_by`; ordinary reads exclude deleted records. Intermediate edit history and JSON snapshots are not stored.
- Reference rows have `active` flags and are not physically removed while referenced. New entries use active references. Existing records retain their stored tare; an Admin correction keeps that tare when the container is unchanged and may retain previously used inactive references.

## API

All operational endpoints require a session. Mutating requests also require the CSRF header obtained from `GET /api/auth/csrf`. Decimal response fields are strings to preserve precision in TypeScript.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Obtain header name and CSRF token, including before login |
| POST | `/api/auth/login` | Form-encoded username/password; 204 on success |
| POST | `/api/auth/logout` | End the session |
| GET | `/api/auth/me` | Account role and current Hobart operational date |
| GET | `/api/container-types` | Active container types and tare values |
| GET | `/api/material-categories?context=CRATE_PALLET` | Active applicable categories; context defaults to Crate/Pallet, or accepts SALVAGE_UP / SALVAGE_DOWN / PICKUP / ELECTRICAL_CRATE_PALLET / COLLECTABLES |
| POST | `/api/crate-pallet-records/preview` | Validate and calculate without saving |
| POST | `/api/crate-pallet-records` | Submit; 201 for creation, 200 for a matching retry |
| GET | `/api/crate-pallet-records?from=YYYY-MM-DD&to=YYYY-MM-DD` | List non-deleted records; optional inclusive date filters |
| GET | `/api/crate-pallet-records/{id}` | Read one non-deleted record |
| PUT | `/api/crate-pallet-records/{id}` | Admin correction |
| DELETE | `/api/crate-pallet-records/{id}` | Admin soft deletion |

Preview accepts optional `?recordId={id}` for an Admin correction, preserving historical tare/reference behavior. The body for preview/submission/correction has `recordDate`, `containerTypeId`, `grossWeight`, and `materials` (`materialCategoryId`, `percentage`). Final POST additionally requires `submissionId`. Admin edit-preview and PUT correction additionally require `version` from a saved-record response. No record status or draft endpoints exist.

Salvage uses the same methods at `/api/salvage-records`: POST `/preview`, final POST, list GET with optional `from`/`to`, detail GET `/{id}`, Admin PUT `/{id}`, and Admin DELETE `/{id}`. Correction preview accepts `?recordId={id}` and requires `version`.

Salvage input contains `recordDate`, `crew`, and separate `up` / `down` objects, each with `time`, `kg`, and optional `materials` (`materialCategoryId`, `percentage`). Final POST requires `submissionId`; corrections require `version`. A zero direction can omit materials or send an empty array. Stale submitted percentages for a zero direction are discarded rather than persisted. Both `time` values and both weights remain mandatory. Responses add IDs/version, category names/calculated material weights, and account/timestamp metadata; decimals are strings.

Duplicate comparison includes the normalized crew, date, both times, both weights, and each direction's normalized positive-percentage rows. Raw calculated weights supplied by clients are ignored; final submission recalculates the accepted values. Both breakdowns and the parent are saved within one transaction.

Pickup uses the same endpoint methods at `/api/pickup-records`, including nonpersistent POST `/preview` and Admin correction preview `?recordId={id}`. Requests contain `recordDate`, `pickupLocation`, `netWeight`, and `materials` (`materialCategoryId`, `percentage`). Final POST requires `submissionId`; Admin PUT requires `version`. Responses include IDs/version, calculated material rows and created/updated metadata, with decimal values as strings. List filtering accepts optional inclusive `from`/`to` dates. Reusing a submission UUID with changed location/date/weight/breakdown or another creator returns 409; identical retries return the existing record.

Electrical Crate/Pallet uses the same workflow endpoints at `/api/electrical-crate-pallet-records`: POST `/preview`, final POST, GET list/detail, Admin PUT and Admin DELETE. Correction preview accepts `?recordId={id}`. Input contains `submissionId`, `recordDate`, `containerTypeId`, `grossWeight`, exactly two material rows (`materialCategoryId`, `percentage`), and `version` for correction. Responses contain both material rows plus applied tare/net weight and standard metadata. List filtering accepts inclusive `from`/`to` dates.

`GET /api/container-types?context=ELECTRICAL_CRATE_PALLET` returns the four active applicable containers; the default context remains `CRATE_PALLET`. `GET /api/material-categories?context=ELECTRICAL_CRATE_PALLET` returns the two active electrical categories. Both context restrictions are validated on writes. Reference rows and their applicability mappings are retained for historical corrections.

Collectables uses the same workflow endpoints at `/api/collectable-records`: POST `/preview`, final POST, list/detail GET, Admin PUT and Admin DELETE. Correction preview accepts `?recordId={id}`. Input contains `submissionId`, `recordDate`, `grossWeight`, `materials` (`materialCategoryId`, `percentage`), and `version` for corrections. The backend supplies fixed tare/net/calculated weights; responses include those stored values and standard metadata. Dates filter inclusively through `from`/`to`. Use `GET /api/material-categories?context=COLLECTABLES` for selectable categories. No container reference endpoint is used.

Whitegoods uses `/api/whitegoods-records`: POST `/preview`, final POST, list/detail GET, Admin PUT and Admin DELETE. Correction preview accepts `?recordId={id}`. Input contains `submissionId`, `recordDate`, `count`, and `version` for correction. Response adds ID/version, `kgPerItem`, `estimatedWeight` and created/updated metadata; weights are strings and count is an integer. List filtering uses optional inclusive `from`/`to` dates. `GET /api/whitegoods-records/monthly-summary?month=YYYY-MM` returns `month`, `totalCount`, `kgPerItem` and `estimatedWeight` for the selected past/current Hobart calendar month. Both roles can read it; malformed/future months are rejected.

Electrical Testing API base is `/api/electrical-testing`. `GET /categories` returns the active dedicated categories. `/tallies` supports POST, date-filtered list GET (`from`/`to`), detail GET, Admin PUT/DELETE, and nonpersistent POST `/tallies/preview` with optional Admin `?recordId={id}`. Tally input is `submissionId`, `recordDate`, nullable `categoryId`, `tallyType`, positive integer `count`, and `version` for correction. POST `/tallies/{id}/reduction` takes `recordDate`, `version` and a non-negative whole `count` strictly below the saved count; zero soft-deletes. Staff may reduce today only; Admin may reduce historical dates. Exact retries of the immediately resulting version/state are acknowledged without another reduction; stale/conflicting changes return 409. New Staff POST tallies are also limited to today. Tally types are `UNTESTED_BROKEN_FAILED`, `TESTED_AND_PRICED`, `STOCK_CULL`, `DIVERTED_TO_OLS`, and `DIVERTED_TO_COLL`.

`/sales-references` supports POST, month-filtered list GET (`month=YYYY-MM`), detail GET, Admin PUT/DELETE, and POST `/sales-references/preview` with optional Admin `recordId`. Input is `submissionId`, `reportingMonth`, `categoryId`, nonnegative integer `soldLastMonthCount`, and correction `version`. `GET /monthly-summary?month=YYYY-MM` returns category totals for the three processing types plus independent overall OLS/Coll totals. Reference values are queried separately and are not added to processing totals. Summed counts use Long. No waste-reporting endpoint consumes these tallies.

## Structure

Backend packages under `au.org.resourcework.waste_tracking_api`: `auth`, `config`, `reference`, `cratepallet`, `salvage`, `pickup`, `electricalcratepallet`, `collectables`, `whitegoods`, `electricaltesting`, `reporting`, `settings`, `ewastepickup`, `scrap`, and `error`. Workflow controllers call services, which own transactions and call repositories, and use DTOs. Flyway SQL lives in `backend/src/main/resources/db/migration`; Hibernate validates rather than creates tables. V1/V2 are preserved, V3 adds the initial schema, V4 seeds reference data, V5 adds Crate/Pallet versioning, V6 adds category applicability, V7 adds Salvage, and V8 adds Pickup and its explicit category mapping. V9 adds Electrical Crate/Pallet, its material definitions/context, and container applicability for normal/electrical Crate/Pallet. V10 adds Collectables and its separate category applicability mapping. V11 adds Whitegoods daily entries with the original NUMERIC(12,2) estimated-weight column; V12 widens that column to NUMERIC(14,2) without changing applied history. Monthly totals are derived queries. V13 adds Electrical Testing categories, additive tallies and monthly sales references.

Frontend: `api` for HTTP/CSRF handling; `features/auth`, `features/crate-pallet`, `features/salvage`, `features/pickup`, `features/electrical-crate-pallet`, `features/collectables`, `features/whitegoods`, and `features/electrical-testing`; workflow-qualified record types in `features/records`; shared decimal/date helpers in `utils`; `pages/RecordsPage.tsx`; and `styles`. Components use local React state and native fetch; no Redux or additional application framework is introduced.

## Verification

Run the backend calculation and isolation-guard unit tests without a database:

```powershell
Set-Location backend
mvn.cmd verify
```

The PostgreSQL integration tests are explicitly gated behind `RUN_DB_TESTS=true` and require runner-supplied `RWC_TEST_DB_*` settings for a verified temporary `rwc_slice_test` database. They delete test records; use the isolated runner from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1
```

The runner starts a loopback-only PostgreSQL instance on a free port, generates temporary credentials, and marks the database with a unique per-run identity token. It removes inherited `SPRING_*` settings and JVM/Maven property injection, loads only the classpath application configuration, and explicitly pins datasource, Hikari, and Flyway URLs/credentials/schema to the temporary database. Original environment values are restored afterward.

Before Spring bean creation or Flyway migration, a test initializer verifies the database URL, name, account, address, port, and identity token and pins the connection properties again. Integration setup rechecks the actual datasource before modifying data. Teardown only runs after successful verification and rechecks isolation before its updates. Unit tests cover redirected connections, wrong identity tokens, property overrides, and teardown after failed setup.

The runner executes 187 tests (37 unit tests and 150 integration tests), packages the backend, and stops PostgreSQL. Salvage tests cover all four zero/positive combinations, mandatory times, independent totals/rounding, correction transitions, exact category sets and API restrictions, historical applicability, concurrency, metadata/permissions, soft deletion, and rollback after an injected material-write failure. Supply `-PostgresBin` to override the default PostgreSQL 18 binary directory. Generated test clusters/logs remain under ignored `backend/target`.

For the complete browser smoke test:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1 -Browser
```

This also builds/lints the frontend and launches temporary backend, Vite preview, and headless Chrome processes. It tests session/CSRF login, percentage validation, review, navigation/logout guards during submission, retaining the retry UUID across a Records visit, definitive conflict handling, Staff read-only access, Admin correction, lost-delete-response/404 reconciliation, selected-detail refresh, and tablet layout. It uses the existing Node/Chrome DevTools smoke runner without adding a framework. Set `CHROME_PATH` if Chrome/Edge is installed elsewhere. Screenshots/logs are under ignored `backend/target/browser-test-*`. Test processes are stopped afterward.

The browser suite also exercises Salvage's distinct category sets, one crew, earlier Down times, all four zero/positive combinations with mandatory times, hidden zero-direction inputs, positive-to-zero and zero-to-positive corrections, stale-version recovery, and retry/navigation across workflow views.

Frontend checks separately:

```powershell
Set-Location frontend
npm.cmd run build
npm.cmd run lint
```

Pickup regression tests cover positive-only weight, required location, exact totals, rounding, scoped category restrictions, retry/concurrent submission, stale correction, metadata/permissions, soft deletion, date filtering and transaction rollback. The browser suite exercises entry/review/edit, zero rejection, recovery after a lost submission response across workflow navigation, Admin corrections and stale versions, and lost-delete/404 reconciliation. Tablet entry/detail screenshots are saved alongside the existing workflow screenshots.

Electrical regressions cover equal/below-tare rejection, exact two-category validation, four-container isolation, tare snapshots across reference changes and container corrections, independent applicability flags, zero-percentage reconstruction, concurrent retries, stale corrections, security/metadata, soft deletion and transaction rollback. The extended browser suite exercises electrical review/save, retry recovery across workflow navigation, date filtering, Staff read-only access, Admin container/tare corrections, stale versions, lost-delete reconciliation and tablet entry/detail layout. Existing normal Crate/Pallet, Salvage and Pickup tests run alongside these tests.

Collectables tests cover fixed tare, gross below/equal/above 2 kg, exact percentage totals, Hobart dates, rounding without redistribution, category restrictions and inactive references, client-calculation overrides, concurrent retries/conflicts, stale corrections, permissions/metadata, date filtering, soft deletion and transactional rollback. Extended browser tests verify the operational reminder and saved-label reminder, absence of extra fields/selectors, review/edit/save, retry recovery across workflows, Admin corrections and stale versions, deleted-record reconciliation and tablet layout. All existing workflow regressions remain in the suite.

Whitegoods tests cover zero and multiple same-day entries, strict integer validation and full INTEGER range, stored factor and calculation integrity, previews, concurrent retries/conflicts, month boundaries including leap February and December/January, empty months, Long monthly sums, corrections moving counts between months, soft-deletion exclusion, optimistic locking, security and metadata. The extended browser suite covers daily entry/review, retry/navigation, same-day and zero entries, independent month/list filters, summary failure/retry, Admin corrections/stale conflicts and deletion-driven summary refresh. All existing workflow regression tests remain included.

Whitegoods migration regression tests verify the original V11 checksum (-931850275), upgrading populated V11 data to V12 without repair, and fresh V1-through-V12 migration/validation in separate schemas of the verified temporary database. Applied migrations must remain unchanged; schema changes belong in new migrations.

Electrical Testing regressions cover the fifteen categories, positive tally and nonnegative reference rules, category/diversion constraints, preview behaviour, independent monthly totals, inactive historical categories, Staff/Admin/CSRF controls, exact and concurrent retries, unique active references, missing versus zero references, replacement/stale updates, soft deletion, large sums and date boundaries. The browser suite exercises category counters, separate overall diversions, positive tally validation, Staff reference submission/read-only behaviour, both request-recovery paths, definitive conflicts, Admin reference corrections/stale versions, tally month/type corrections, deletion reconciliation and updated monthly totals. The full suite retains all existing workflow and Whitegoods migration-history tests.


## Admin reporting

Staff land on Operations and retain read-only submitted Records, the Whitegoods operational summary and Electrical Testing counters. Admins land on Overview and additionally have Reports and Waste Levy Summary. Both roles reuse the existing workflow components. Management pages are guarded by role in React; all `/api/admin/**` endpoints require Admin and reporting/export services enforce the role again. Settings is Admin-only; there is no Users link.

Overview shows submissions created today (Hobart local-midnight boundaries), operational record counts this month, workflow activity, separate monthly weights/counts, five recent submissions and six months of workflow activity. Sales references are excluded from operational submission counts. Clicking an operational record opens the existing Records detail/correction view.

Reports support daily, Monday-Sunday weekly, calendar-month, July-June financial-year and custom inclusive date ranges. Period presets are based on the From date. Apply filters before exporting. Filters are workflow, material category, Pickup location substring, Salvage direction, and a separate Electrical Testing category. Incompatible backend filter combinations are rejected. Material filters show matching material rows only. Historical inactive reference categories remain reportable.

All operational report dates use `recordDate`; Submitted today uses `created_at`. Scrap and sales-reference rows use the reporting month and include each month touched by the filter; they are not prorated, added to tally totals or implicitly filled with zero. Detailed reports have explicit RECORD, DIRECTION, MATERIAL and REFERENCE row types: do not sum parent net weights and their category breakdowns together. A record key is `(workflow, id)`. Records and detailed Reports use server-side pagination: default 10 rows, selectable sizes 10/25/50. Exports include all filtered rows, not just the visible page.

The Monthly Waste Levy Summary is a **monthly working aid for manual entry into the existing workbook**, not a completed Waste Levy return, NRE reconciliation or certified report. Sections are Salvage Down, general intake by workflow/container, combined normal general-intake categories, Salvage Up, Electrical Crate/Pallet and Whitegoods estimates. Container Types Settings provides a Waste Levy summary group (Crate, Pallet or Other), required for Crate/Pallet applicability. New Crate/Pallet records snapshot that group; later configuration changes do not reclassify old records. Same-container corrections retain the original group, while changing the selected container applies its current group. V20 backfills exact legacy snapshot names: Apple Crate/Stillage Cage as CRATE and Plastic/Pine/Hardwood Pallet as PALLET; unknown legacy names safely become OTHER, without dropping weight. The MSW matrix uses stored group snapshots and shows Other containers only for a non-zero monthly contribution. Electrical Crate/Pallet and E-waste Pickup are unaffected. Generic report/export rows remain unchanged. Combined general intake includes only normal Crate/Pallet, Collectables and Pickup. Electrical categories remain separate. E-waste Pickup, legacy E-waste reconciliation/carry-over, residual reconstruction, 56/44 assumptions, 80/20 C&I/MSW allocation and AnnualReturn/NRE formulas are excluded.

Reporting sums stored material weights without recalculating percentages or redistributing rounding differences. Net/direction weights may differ slightly from summed rounded categories. Whitegoods estimates sum the stored estimated weights and show applied factors separately. Default display is kg with two decimals; optional summary tonnes use kg/1000 with five decimals. No report claims automatic cross-workflow deduplication or unique diversion. Queries are ordinary read-only transactions over existing tables; there are no reporting migrations, persisted monthly totals, special snapshot infrastructure or caches. Refresh after corrections/deletion; a later export reflects current data and can differ if records changed since the displayed result.

### Management endpoints

All require Admin session authentication:

- `GET /api/admin/overview`
- `GET /api/admin/reports/options`
- `GET /api/admin/reports/summary`
- `GET /api/admin/reports/records?page=0&size=10`
- `GET /api/admin/reports/electrical-testing`
- `GET /api/admin/reports/export?report=summary|details|electrical-testing&format=csv|xlsx`
- `GET /api/admin/waste-levy-summary?month=YYYY-MM`
- `GET /api/admin/waste-levy-summary/export?month=YYYY-MM&format=csv|xlsx`

Report filters are `from`, `to`, `workflow`, `materialCategoryId`, `location`, `direction` and `testingCategoryId`. Dates default to current month-to-date in Australia/Hobart. The KPI endpoint fixes the workflow to Electrical Testing; its export requires that workflow filter. JSON weights are decimal strings.

CSV uses UTF-8 with a BOM, quoted columns and CRLF records; metadata/filter columns accompany every row. Empty results retain headers and report context. Formula-like user text is prefixed with an apostrophe. XLSX uses Apache POI with literal text cells (never user-generated formulas), numeric values where Excel precision permits, and exact decimal text where it does not. Metadata includes the period/filters, units or unit-bearing columns, exclusions and generation time. No legacy spreadsheet formulas are emitted. Summary workbooks use Summary; detailed exports separate parent records, material/direction breakdowns, Whitegoods, Electrical Testing KPIs and sales references when applicable. Monthly exports have one Monthly Summary sheet. All weights in exports are kg. Filenames include the date range, or `waste-levy-summary-YYYY-MM.csv|xlsx`. Detailed exports above 100,000 rows are rejected with an instruction to narrow filters; no truncation or background export machinery is used.

Reporting tests cover Admin boundaries, category/direction/count separation, filtered totals/details, zero references, soft deletion/date corrections, stored rounding, financial-year/leap-day ranges, pagination validation, CSV escaping and formula protection, XLSX literal/typed cells and sheet separation, stable monthly filenames/exclusions, and Hobart DST day boundaries. Browser checks cover role navigation, periods/applied filters, CSV/XLSX buttons, detail links, monthly kg/tonnes, deletion refresh and tablet layout, alongside all existing workflow smoke tests.


## Admin Settings

Admin navigation has a top-right **Settings** dropdown, separate from the primary Overview/Workflows/Reports/Waste Levy Summary/Records links. Staff have neither the dropdown nor access to Settings pages. `/api/admin/**` authorization and controller/service role checks protect every Settings endpoint; CSRF is required for writes. Existing Staff operational summaries remain available. User Management is Admin-only and supports creation, role changes, activation/deactivation and password resets. Electrical Testing category configuration is not included.

Settings opens one focused page at a time at `/admin/settings/container-types`, `/admin/settings/material-categories`, `/admin/settings/organisation`, `/admin/settings/reporting`, or `/admin/settings/whitegoods`. `/admin/settings/users` provides User Management. These direct URLs load the selected page for Admin; Staff remain on Operations. No routing library is added. Settings contains five configuration sections, plus User Management described below:

- **Container Types:** add, edit display name/tare, change applicability (`CRATE_PALLET`, `ELECTRICAL_CRATE_PALLET`, `EWASTE_PICKUP`), activate/deactivate and view status. Tares allow 0 to 99,999,999.99 kg with at most two decimals. Active references need at least one workflow. Names are case-insensitively unique, including inactive identities, to avoid historical ambiguity. No delete endpoint exists.
- **Material Categories:** add, activate/deactivate and change applicability for Crate/Pallet, Salvage Up/Down, Pickup and Collectables. Names are immutable; add a distinct replacement rather than rename historical meaning. The electrical TVs/Monitors/Projectors and Everything Else categories are read-only and cannot be merged into normal waste categories. E-waste, Lead Acid Batteries and Whitegoods/Large Appliances retain their approved Salvage restrictions. Electrical Testing uses its separate unchanged fifteen-category table.
- **Organisation Details:** organisation name, optional site display name and report/export display name. Names are required except site and limited to 100 characters. They affect future-facing branding and export headers, not operational rows.
- **Reporting Defaults:** initial kg/tonnes display, current month/current financial year selection and financial-year start month (July initially). Reports default to month/FY-to-date. The FY preset uses the configured start month. Dates/weights remain explicitly filterable; exports retain their documented kg units. Operational timezone is fixed as Australia/Hobart and is not editable.
- **Whitegoods Weight Factor:** positive BigDecimal value, at most two decimals, 0.01 to 999.99 kg/item, initially 48.00. Future submissions use the configured value; corrections retain the entry's original factor. The form estimate and backend preview use the configuration; the backend always recalculates final values. Existing retry UUIDs return their original saved values after a configuration change.

The three organisation/reporting/factor forms share an explicit singleton settings row. Each focused page loads its current configuration and mounts only that section's existing form. Optimistic versions reject stale configuration/reference updates with 409; Reload settings refreshes only the selected editor after a conflict. Configuration records track the last Admin and modification time; reference changes have corresponding `settings_updated_by`/`settings_updated_at` metadata. No generic key/value settings or audit-history engine is used.

### Settings APIs

- `GET /api/admin/settings`: organisation/reporting/factor configuration and version.
- `PUT /api/admin/settings`: update explicit configuration fields using the current version; each UI form submits its section merged with current values.
- `GET`, `POST /api/admin/settings/container-types`; `PUT /api/admin/settings/container-types/{id}`.
- `GET`, `POST /api/admin/settings/material-categories`; `PUT /api/admin/settings/material-categories/{id}`.

Reference writes contain `name`, `active`, `contexts`, `tareWeight` for containers, and `version` for updates. New reference creation currently returns 200 with the created DTO. There are no hard-delete endpoints. The authenticated read-only `GET /api/operational-configuration` exposes branding, the future Whitegoods factor and fixed timezone to operational forms; it exposes no administrative write functions or reporting defaults to Staff.

### Migration and historical safety

**V14__admin_settings.sql** creates the explicit singleton configuration, reference version/modifier columns and case-insensitive name indexes. It backfills `container_name` snapshots on existing normal/electrical records. A small insert/container-change trigger captures future names and preserves existing snapshots on ordinary corrections. Reference display-name/tare edits never update historical operational records or their versions/timestamps; reports use the snapshot name. Existing tare snapshots and material breakdowns remain unchanged.

Applicability changes mark existing links inactive instead of deleting them. Historical identities remain resolvable and inactive categories remain reportable. All previous migrations, including the original V11 checksum and V12 expansion, remain unchanged. V14 replaces the Whitegoods fixed-48 constraint with a positive-factor constraint and expands estimated weight to NUMERIC(15,2), retaining the database count-times-factor equality check. Monthly estimates sum saved entry estimates; they never apply today's factor retrospectively. An empty month's report does not claim that today's factor was historically applied.

Settings integration tests cover Admin/Staff/anonymous/CSRF boundaries, defaults and validation, stale versions, container creates/duplicates/applicability/deactivation, historical normal/electrical name/tare snapshots, material identity and inactive historical reporting, unchanged Electrical Testing categories, changed future factors, retries/corrections, mixed-factor monthly summaries and maximum count/factor precision. A separate verified-schema migration test upgrades populated V13 to V14 and checks the historical snapshots/weights. The browser suite additionally exercises inline Settings editing, native validation, role navigation/direct Staff URL denial, updated branding/defaults and new-versus-historical Whitegoods factors alongside all existing workflow/reporting regressions.


V14 migration history is restored to the originally applied checksum **-548343575**. Its original `report_period VARCHAR(20)` definition is immutable. **V15__admin_settings_followup.sql** expands that column to VARCHAR(32), allowing `CURRENT_FINANCIAL_YEAR` without repair. Migration regressions verify a populated original V14 upgrades to V15 with settings and operational rows unchanged, and fresh V1-through-V15 schemas migrate/validate successfully. Container-name/tare snapshots and NUMERIC(15,2) Whitegoods precision remain unchanged.


## Admin Dashboard V2

Admin primary navigation contains Overview, Workflows, Reports, Waste Levy Summary and Records. The top-right **Settings** menu opens six focused destinations, including User Management. It closes after selection, an outside pointer/click or focus departure, and supports arrow keys, Home/End and Escape with focus restoration. Admin operational entry is available through the existing **Workflows** cards; the former global + New Entry menu is removed. Busy submissions disable navigation; unresolved submissions permit only resuming their current workflow. Workflows is a responsive grid of eight operational cards with descriptions and New Entry actions. Staff use Operations and Records navigation. Operations provides nine workflow cards and the latest five active operational submissions, ordered by submission time across all dates; the same read-only records are accessible regardless of submitting role. Electrical Testing sales references remain on their operational screen rather than in this recent-tally list. Staff retain existing operational summaries; no Admin menu or management pages are exposed. An unresolved entry can be resumed from its Operations card without losing the payload or submission ID. The authenticated GET /api/operations/recent-submissions endpoint returns only compact record details, not reporting aggregates.

Overview defaults to the current Hobart calendar month and optionally selects another past month. Its four primary cards show record count, council-provided Salvage Down weight, council-provided Salvage Up weight, and received Whitegoods count with stored estimated weight. Submitted today is a secondary indicator across all operational dates. The label Whitegoods processed refers to the existing received-count entries; no new processing/inventory state is inferred.

Five lightweight charts are provided through Chart.js 4 and react-chartjs-2 5: six-month workflow activity, selected-month workflow record counts, general-intake material weights, Salvage recovered versus landfill, and the five Electrical Testing tally statuses. Chart code is lazy-loaded only when the Admin dashboard renders. Canvas charts have accessible labels and exact-value text alternatives. Plot coordinates use JavaScript numbers only for rendering; weight aggregation stays in backend BigDecimal calculations, and weight tooltips/alternatives use the original decimal strings. Sales-reference values never enter activity counts. Electrical Testing record activity counts tally submissions, not individual items or a stock balance.

`GET /api/admin/overview?month=YYYY-MM` extends the existing Admin-only API; omitting month means current month. Invalid/future months are rejected. It returns the existing monthly details plus `month`, `metrics`, `generalIntake` and `electricalTesting`. Nine workflow activity buckets and six months x nine trend buckets are zero-filled on the server. General intake reuses the monthly summary's approved Crate/Pallet + Collectables + Pickup category aggregation, excluding all other workflows. No new repository queries, summary tables, migrations or security rules are needed.

Recent submissions show the latest five active operational entries across all dates and link to Records; View all records opens the existing Records page. Monthly quantities remain collapsed by default below the visual overview. Empty charts show a clean no-activity state without axes. Desktop and tablet layouts resize charts and wrap KPI/navigation controls. No grand material total, diversion rate, regulatory allocation, legacy NRE logic has been introduced.

Dashboard regressions verify KPI/source counts, safe general-intake exclusions, independent Salvage directions, separate Electrical Testing counters, zero-filled trend periods, recent-row limits, soft-deletion exclusion and empty/invalid months. Extended browser smoke checks cover all nine Admin workflow destinations and six Settings destinations, workflow cards, keyboard/outside-close interaction, Staff separation, chart/KPI values against API fixtures, deletion refresh, empty states, and desktop/tablet screenshots and overflow, together with all existing workflow, Reports/export, Waste Levy Summary and Settings checks.


## E-waste Pickup: collection / intake

E-waste Pickup is implemented and is permanently separate from **Electrical Crate/Pallet internal processing**. One entry represents a collection: operational record date, required free-text organisation, positive entered weight and optional container lines. There is no material-category breakdown, Gross/Net toggle, cross-workflow matching or legacy reconciliation.

With no containers, entered weight is the net E-waste weight and tare is zero. With containers, entered weight is gross: subtract the sum of quantity multiplied by configured per-unit tare. Quantities must be positive whole numbers; entered weight and final net must be positive and support two decimals. Weights use BigDecimal on the backend and integer hundredths for browser estimates. Review uses server calculations; final POST recalculates independently.

The organisation field suggests up to twenty distinct names from active submitted E-waste Pickups, using a case-insensitive substring query. Selecting a suggestion reuses its exact spelling. Names are trimmed on preview/save; new names are accepted without fuzzy merging or a separate organisation entity.

Admin assigns active container types to **EWASTE_PICKUP** in Settings > Container Types. The migration deliberately does not guess initial applicability; with no assigned containers, the no-container flow still works. Existing container names, per-unit tares, quantities and subtotal tares are snapshotted in `ewaste_pickup_container`, keyed by pickup/container type. The parent `ewaste_pickup_record` stores entered weight, total tare, positive net weight, UUID, version and standard created/updated/soft-delete metadata. Corrections retain snapshots for existing container identities, even if later inactive or removed from applicability. Newly added types use current active configuration.

Staff create/view; Admin correct/soft-delete with optimistic version checks. Entry > Review > Confirm preserves the existing in-memory payload/UUID recovery and navigation guards. Identical retries return the saved snapshot, even after reference configuration changes. Records and recent submissions include this workflow independently.

Endpoints:

- `GET /api/ewaste-pickups?from=&to=` and `GET /api/ewaste-pickups/{id}`
- `GET /api/ewaste-pickups/organisations?query=`
- `POST /api/ewaste-pickups/preview` (optional Admin correction `recordId`)
- `POST /api/ewaste-pickups`, `PUT /api/ewaste-pickups/{id}`, `DELETE /api/ewaste-pickups/{id}`
- Existing `GET /api/container-types?context=EWASTE_PICKUP`

Reporting uses **stored net collected weight**, separately from Electrical Crate/Pallet. The Reports organisation filter reuses the existing `location` source-text query parameter with `workflow=EWASTE_PICKUP`. Detailed CSV/XLSX adds `organisation`, `entered_weight` and `container_lines`; gross_kg is left empty for this workflow because entered weight may be net. Container-line export text includes historical name, quantity, unit tare and subtotal. Filters apply to all exports and deleted records are excluded. User-entered organisation text retains formula-injection protection.

Dashboard activity counts and six-month workflow trends include E-waste Pickup. There is no new headline weight KPI, and its weight is excluded from general material-category intake, Salvage, Whitegoods and electrical processing totals. The Monthly Waste Levy Summary deliberately continues to exclude E-waste Pickup; Reports provides the independent collection figures. No carry-over, 56/44, 80/20, residual, AnnualReturn/NRE or Monthly Return Crunching logic is applied.

`V16__ewaste_pickup.sql` adds only the two new tables and active-date index. V1-V15 remain unchanged; no Flyway repair is used. Tests cover fresh V1-V16 and a populated V15 upgrade with unchanged old migration checksums and historical weights.


## Scrap: additive monthly material weights

Scrap is implemented as a separate operational workflow. Each submission contains a reporting month and exactly five weights: Iron and Steel, Aluminium, Other Non-Ferrous, Mixed Metals and E-waste. These fixed Scrap fields do not reuse or change configurable intake categories. Weights use BigDecimal / NUMERIC(10,2); individual zeros are valid, negatives and all-zero entries are rejected. Multiple submissions for the same month are valid.

The reporting month is stored as a DATE on the first of the month and exposed as YYYY-MM, using the existing monthly-reference convention. It is a reporting period, not a manually entered submission date. Valid months use years 0001-9998; no extra future-month restriction is introduced. Initial month selection uses Australia/Hobart. Submission account and timestamps are automatic.

Entry > Review > Confirm uses the existing in-memory submission UUID/recovery and busy/navigation/logout protections. Preview persists nothing. The review shows all five materials and overall totals for current active records, this entry and projected totals. Correction previews exclude the original record from the selected month before adding the corrected values. These are live previews, not reservations; another submission may change totals before confirmation.

Monthly summaries are derived from active records, with no monthly aggregate table. Admin corrections, month changes and soft-deletions immediately affect summaries. Staff create/view only; Admin corrections use optimistic versions and deletion retains standard metadata.

Endpoints under `/api/scrap-records`:

- `POST /preview` (optional Admin correction `recordId`)
- `POST /` final submission
- `GET /?from=&to=` and `GET /{id}`
- `PUT /{id}` and `DELETE /{id}` for Admin
- `GET /monthly-summary?month=YYYY-MM`

Records, Reports, detailed records and CSV/XLSX exports include Scrap independently. Date ranges include Scrap reporting months touched by the range; export reporting dates use YYYY-MM. Detail exports include a parent total and five labelled material rows, which must not be added to their parent again. Normal material-category filters do not apply to these fixed fields. The Monthly Waste Levy Summary now includes a separate SCRAP section, including zero values for missing activity. E-waste Pickup remains excluded from that working aid. Dashboard workflow activity counts include Scrap, while its weights are excluded from top intake, general intake, Salvage, electrical processing and E-waste Pickup quantities.

`V17__scrap.sql` creates only `scrap_record`, with five non-negative NUMERIC fields, positive overall entry check, UUID/version/metadata and an active reporting-month index. No stored totals or additional operational fields are added. V1-V16 remain unchanged. No carry-over, 56/44, 80/20, residual reconstruction, Monthly Return Crunching or NRE reconciliation is implemented.


## Server-side table pagination

Records uses authenticated `GET /api/records?workflow=&from=&to=&page=0&size=10`. Omitting workflow selects all nine operational workflows. One PostgreSQL UNION ALL query applies workflow/date filters and deterministic date-descending, ID-descending, workflow ordering before LIMIT/OFFSET; it does not page and merge separate workflow lists. A separate count uses the same predicates. Only IDs on the requested page are hydrated through existing workflow DTO services. Electrical Testing sales references remain separate from operational tally records. Scrap filters retain reporting-month semantics.

The response is `{items: [{workflow, record}], page, size, totalItems, totalPages}`. Pages are zero-based in APIs and one-based in the UI. Only sizes 10, 25 and 50 are accepted. Negative/unreasonable page values are rejected; a page that no longer exists is normalised to the last valid page (or page zero for an empty dataset). Workflow-specific legacy list/detail APIs remain compatible; the Records screen no longer loads their full lists.

Detailed Reports keeps its existing `{rows, totalRows, page, size, ...}` response and report predicates, now with the same 10-row default and approved sizes. Material/direction/reference rows retain their existing meanings. A final category-name sort makes fixed Scrap material rows deterministic when category IDs are null.

Compact Previous/Next, current-page buttons, ellipses, filtered row counts and rows-per-page controls appear below each paginated table. Filter, report-type and page-size changes reset to the first page. Records and detailed Report filters/page/size are kept in application memory while an Admin corrects a record; saves/deletions refresh the current filtered page, falling back to a valid page when necessary. Existing unresolved-submission/navigation/logout guards remain in place.

CSV/XLSX exports continue to use all matching filtered rows, independent of the selected screen page, with the existing 100,000-row safety limit and formula protection. Recent-submission limits, dashboard charts, monthly summaries and small Settings reference tables are unchanged. No schema migration, new index, framework or caching infrastructure is required.

## Admin User Management

Open **Settings > User Management** as an Admin. The server-paginated table uses 10, 25 or 50 rows with deterministic username/ID ordering. Staff cannot access the page or its APIs.

- Create STAFF or ADMIN accounts. New usernames are trimmed, immutable and case-insensitively unique, including inactive accounts. Sign-in continues to use the stored spelling, matching existing authentication.
- Passwords follow the existing non-blank policy and BCrypt's maximum of 72 UTF-8 bytes. Confirmation is checked in the frontend; passwords are hashed and never returned by APIs.
- Change another account's role, deactivate/reactivate it, or reset its password. Accounts are never hard-deleted. Ask another Admin to reset your own password.
- Self-deactivation and self-demotion are prohibited. Backend transactions serialize account changes and always retain at least one active Admin, including concurrent changes.
- Role, status and password changes expire the affected account's sessions after commit. Deactivated accounts cannot sign in; password reset immediately replaces the old password.
- Stale versions return HTTP 409; use **Reload users** to recover. Existing operational ownership and historical references remain unchanged.

Endpoints (all Admin-only; CSRF required for writes):

- `GET /api/admin/users?page=0&size=10&query=staff`
- `POST /api/admin/users`
- `PUT /api/admin/users/{id}/role`
- `PATCH /api/admin/users/{id}/status`
- `PUT /api/admin/users/{id}/password`

Migration **V18__user_management.sql** reuses `app_user`, adding active status, optimistic version and nullable creation/update actor/timestamp metadata. Existing accounts remain active. Unknown legacy timestamps display **Not recorded** rather than invented dates. The former single-STAFF index is removed to support approved account creation and role changes; no second user table is created. V1?V17 remain unchanged. No email reset, MFA or external identity service is introduced.

## Summary table pagination

Reports Summary uses server-side aggregate pagination: `GET /api/admin/reports/summary` accepts `page` (zero-based, default 0) and `size` (10/25/50, default 10), returning `totals`, `totalItems`, `totalPages`, `page` and `size` alongside the existing filter/time metadata. Filters and aggregation run before pagination, with deterministic ordering. Applied filter and page-size changes reset to page one; invalid final pages fall back to the last valid page.

The Monthly Waste Levy Summary is a compact, unpaginated monthly worksheet. Salvage Up/Down show category weights with measured direction totals and record counts; MSW uses a Crate/Pallet, Collectables and Pickup category matrix; combined intake and Scrap show category/weight tables; Electrical uses a container/material matrix; Whitegoods shows count, stored estimated weight and all applied historical factors. Matrices sum the displayed stored material weights exactly, retaining rounding differences from measured weights. Tables scroll within their section on smaller tablets. CSV/XLSX retain the complete existing backend export structure in kg; no legacy reconciliation or allocation formulas are added.

Pagination changes screen display only. CSV/XLSX exports retain all matching summary rows and all selected-month sections in their existing order and structure. The existing 100,000-row detailed-export safety limit remains unchanged. No schema or migration change is required.

User Management includes case-insensitive literal username search. Search is applied before server-side pagination; applying/clearing search resets the list to page one. The existing STAFF/ADMIN creation, role/status confirmations, password reset, stale-update handling, last-active-Admin protection and historical identity references remain available. Own password resets continue to require another Admin.

**V19__usernames_case_insensitive_unique.sql** adds a unique index on `lower(username)` to the existing `app_user` table. No user columns or second user table are added. Existing migrations V1?V18 are unchanged. If legacy accounts differ only by case, V19 stops with an explicit error; it never automatically merges, renames or deletes accounts. Account identities must be resolved deliberately before retrying the migration. Bootstrap provisioning also rejects a new initial username that differs only by case from an existing account.

## DEV Cloud Run deployment (prepared; not deployed)

One Cloud Run service serves the Vite production build and Spring Boot REST API from the same origin. Vite remains the local development server/proxy; normal local PostgreSQL settings are unchanged. No Actuator, external session store or new migration is added. Operational dates remain Australia/Hobart.

`Dockerfile` builds with Node 24, Maven/Java 21, then copies only the executable jar into a Java 21 JRE image running as UID/GID 10001. Frontend assets are packaged under Spring Boot static resources. The existing six Settings deep links forward to index.html; API and missing asset paths never use SPA fallback. The HTML/assets are public so login can load; API authentication, Admin authorization and CSRF remain enforced.

### Existing DEV resources and runtime configuration

- Project: `rwc-waste-tracking-dev`; region: `australia-southeast2`.
- Artifact Registry repository: `rwc-waste-tracking`.
- Image: `australia-southeast2-docker.pkg.dev/rwc-waste-tracking-dev/rwc-waste-tracking/rwc-waste-tracking:BUILD_ID`.
- Cloud Run service: `rwc-waste-tracking-dev`.
- Runtime service account: `rwc-cloud-run-dev@rwc-waste-tracking-dev.iam.gserviceaccount.com` (existing Cloud SQL Client and Secret Accessor grants).
- Cloud SQL: `rwc-waste-db-dev`; database `rwc_waste_tracking`; database user `rwc_app`.

| Environment variable | DEV value/source |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `PORT` | Injected by Cloud Run; prod falls back to 8080 |
| `DB_URL` | Explicit PostgreSQL socket JDBC URL below |
| `DB_USERNAME` | `rwc_app` |
| `DB_PASSWORD` | Secret `rwc-db-password` |
| `INITIAL_ADMIN_USERNAME` | Cloud Build `_ADMIN_USERNAME`, default `admin` |
| `INITIAL_ADMIN_PASSWORD` | Secret `rwc-initial-admin-password` |
| `INITIAL_STAFF_USERNAME` | Cloud Build `_STAFF_USERNAME`, default `staff` |
| `INITIAL_STAFF_PASSWORD` | Secret `rwc-initial-staff-password` |

The usernames are non-secret DEV defaults, configurable through substitutions. Existing account spellings must be reused. Passwords are supplied only by runtime Secret Manager mappings, never by Docker build arguments or source files. DEV uses `rwc-db-password:latest`, `rwc-initial-admin-password:latest` and `rwc-initial-staff-password:latest`. The newest version of each secret must be enabled and accessible. [Secret Manager `latest`](https://docs.cloud.google.com/secret-manager/docs/reference/rest/v1/projects.secrets.versions/access) means the most recently created version; it does not skip disabled versions to find an older enabled one. No secret-version substitutions are needed. Runtime service-account credentials come from Cloud Run, never a committed JSON key.

Production `DB_URL`:

```text
jdbc:postgresql:///rwc_waste_tracking?socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlInstance=rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev&unixSocketPath=/cloudsql/rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev/.s.PGSQL.5432&sslmode=disable
```

Cloud Run attaches `rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev` and supplies its authenticated Cloud SQL proxy socket. The Google PostgreSQL socket-factory dependency opens that local Unix socket; no Cloud Run IP allowlist is needed. `sslmode=disable` applies only to the local Unix socket hop; the managed Cloud SQL proxy protects the remote connection. This configuration assumes the standard public-IP Cloud SQL proxy path. If the instance is private-IP-only, confirm existing VPC connectivity first; this repository does not create a VPC.

The prod profile requires DB_URL/DB_USERNAME/DB_PASSWORD rather than falling back to a local database. It uses PORT, a five-connection maximum pool, HTTPS-only session cookies and forwarded HTTPS headers. Local default properties are unchanged. Do not set SESSION_COOKIE_SECURE=false on Cloud Run.

First startup runs the existing Flyway V1-V19 migrations before Hibernate schema validation. An empty database receives the full schema; later startups apply only missing migrations. Ensure `rwc_app` has USAGE/CREATE on the application's public schema and ownership/DDL rights for its migrated objects; Cloud SQL Client IAM alone does not grant PostgreSQL schema privileges. No repair, clean, reset or migration rewrite is used. Bootstrap creates absent initial users and never resets existing passwords, roles or status. Provision both initial accounts on the first launch; remove bootstrap passwords from later revision configuration when no longer needed.

### Cloud Build and first-deployment prerequisites

`cloudbuild.yaml` performs, in order:

1. Build/run the `backend-tests` Docker target: all tests against a newly created, identity-verified local PostgreSQL cluster using `scripts/test-backend-linux.sh`. It explicitly overrides datasource/Flyway settings and never connects to Cloud SQL. Integration tests are not skipped.
2. Build the frontend target (`npm ci`, lint, build).
3. Build the runtime image using cached build layers.
4. Push the uniquely tagged BUILD_ID image to Artifact Registry.
5. Deploy that exact image/tag to the DEV service with its dedicated runtime identity, Cloud SQL attachment and secret mappings.

The GitHub trigger must target this file on an approved deployment branch. A build submission WILL deploy once tests pass; no Cloud Build was submitted during repository preparation.

Before first submission, manually verify (do not grant these roles to the runtime identity merely to make builds work):

- GitHub repository connection/Cloud Build trigger and required APIs are enabled (`cloudbuild`, `artifactregistry`, `run`, `sqladmin`, `secretmanager`).
- The selected **build service account** has Artifact Registry Writer on the repository, Cloud Run deployment/IAM-policy permissions (for example Cloud Run Admin), Service Account User on the runtime account, and Logs Writer for CLOUD_LOGGING_ONLY. These are separate from the already granted runtime Cloud SQL/secret roles. The submitting user must be allowed to submit builds/use that build identity.
- The latest secret versions are enabled and accessible; database/schema privileges and Cloud SQL connectivity match the configuration above. Public unauthenticated invocation must be permitted by project/org policy.
- Review the deployment diff and approve deployment before submitting or enabling automatic deploy triggers.

After approval, a manual PowerShell submission from the repository root is:

```powershell
gcloud builds submit . --project=rwc-waste-tracking-dev --region=australia-southeast2 --config=cloudbuild.yaml --substitutions=_ADMIN_USERNAME=admin,_STAFF_USERNAME=staff
```

Both GitHub-triggered and manual builds use Cloud Build BUILD_ID tags, not latest. Commit SHA is recorded as an image label when supplied by the source trigger. `.dockerignore` and `.gcloudignore` exclude local secrets, build output, dependencies and test artefacts. Build/runtime base versions are explicit; refresh them deliberately as part of maintenance.

For review, the equivalent deploy command (DO NOT execute until deployment approval) is:

```powershell
$image = 'australia-southeast2-docker.pkg.dev/rwc-waste-tracking-dev/rwc-waste-tracking/rwc-waste-tracking:BUILD_ID'
$jdbcUrl = 'jdbc:postgresql:///rwc_waste_tracking?socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlInstance=rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev&unixSocketPath=/cloudsql/rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev/.s.PGSQL.5432&sslmode=disable'
gcloud run deploy rwc-waste-tracking-dev `
  --project=rwc-waste-tracking-dev --region=australia-southeast2 --platform=managed `
  --image=$image `
  --service-account=rwc-cloud-run-dev@rwc-waste-tracking-dev.iam.gserviceaccount.com `
  --add-cloudsql-instances=rwc-waste-tracking-dev:australia-southeast2:rwc-waste-db-dev `
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod,DB_USERNAME=rwc_app,INITIAL_ADMIN_USERNAME=admin,INITIAL_STAFF_USERNAME=staff,DB_URL=$jdbcUrl" `
  --set-secrets=DB_PASSWORD=rwc-db-password:latest,INITIAL_ADMIN_PASSWORD=rwc-initial-admin-password:latest,INITIAL_STAFF_PASSWORD=rwc-initial-staff-password:latest `
  --min-instances=0 --max-instances=1 --cpu=1 --memory=512Mi --timeout=300s `
  --allow-unauthenticated --quiet
```

Replace BUILD_ID with the pushed build tag and select the reviewed username substitutions. Secret references remain `latest`. No secret values are present in this command.

The complete generated deploy command is the `deploy-dev` step in `cloudbuild.yaml`. It uses min instances 0, **max instances 1 (approved first-DEV cap)**, CPU 1, memory 512 MiB, timeout 300 seconds, default concurrency, and allow-unauthenticated Cloud Run invocation; the application still authenticates API calls. No GCP resources were changed during preparation.

Sessions and account-change revocation remain in process memory. Max 1 avoids normal multi-instance session routing/revocation inconsistencies, but restarts, scale-to-zero and revision rollouts still require users to sign in again. Do not enable traffic splitting or assume sessions/drafts survive deployments. Shared sessions and reliable multi-instance revocation are outside this slice. Cloud Run limits are not an availability/session-persistence guarantee. Heap is capped at 50% of container RAM; start DEV at 512 MiB if local validation passes, monitor memory/export workloads, and raise to 1 GiB if representative large XLSX exports approach the limit. Local startup smoke tests are not a production load test.

### Local deployment validation

With Docker Desktop running (no GCP access is used):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-backend.ps1
npm.cmd --prefix frontend run lint
npm.cmd --prefix frontend run build
docker build --target backend-tests -t rwc-backend-tests:dev-validation .
docker build -t rwc-waste-tracking:dev-validation .
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-container.ps1
```

The container smoke test uses an isolated Docker network, new PostgreSQL container and random test credentials in temporary ignored env files. It tests the prod profile at PORT=9090, static shell/assets/deep link, API 401/403, authentication/CSRF, XLSX export, startup migrations, non-root execution, 512 MiB memory and bootstrap password retention after restart. It removes only its own containers/network/env files. It temporarily overrides secure cookies only for its loopback HTTP test. Cloud Run keeps secure cookies enabled. Container logs/memory samples remain under ignored backend/target. Actual Cloud SQL/Secret Manager connectivity must be confirmed at the first approved deployment.

### Logs and rollback (commands for later approved operations)

```powershell
gcloud run services logs read rwc-waste-tracking-dev --project=rwc-waste-tracking-dev --region=australia-southeast2 --limit=100
gcloud builds log BUILD_ID --project=rwc-waste-tracking-dev --region=australia-southeast2
gcloud run revisions list --service=rwc-waste-tracking-dev --project=rwc-waste-tracking-dev --region=australia-southeast2
# Mutates traffic: execute only when rollback is intended/approved.
gcloud run services update-traffic rwc-waste-tracking-dev --to-revisions=PRIOR_REVISION=100 --project=rwc-waste-tracking-dev --region=australia-southeast2
```

A traffic rollback does not undo database migrations. Keep revisions schema-compatible, retain the prior image, verify that the current latest secret values are compatible with the prior revision, and review database backup/recovery before future schema-changing deployments. Runtime logs go to stdout/stderr and Cloud Logging; never log credentials or put secret values into build substitutions.

Deployment references: [Google Cloud SQL Unix socket sample](https://docs.cloud.google.com/sql/docs/postgres/samples/cloud-sql-postgres-servlet-connect-unix), [Cloud Build deployment configuration](https://docs.cloud.google.com/build/docs/deploying-builds/deploy-cloud-run), and [Cloud Run session-affinity limitations](https://docs.cloud.google.com/run/docs/configuring/session-affinity).
