# BerryHarvest (Збір Ягід)

Android app for managing berry farm operations — workers, harvest tracking, payments, and reporting. Designed for field use with offline-first architecture and cloud sync.

## Overview

BerryHarvest handles the day-to-day workflow of a berry farm: registering workers, assigning field rows, recording harvest quantities, processing payments, and generating reports. The app works fully offline and syncs to Supabase when connectivity is available.

## Tech Stack

- **Android**: Kotlin, Jetpack Compose, Room 2.6.1, MVVM, Coroutines, StateFlow
- **Backend**: Supabase (PostgreSQL + Postgrest-kt + Realtime-kt over Ktor)
- **QR Scanning**: ZXing (worker identification)
- **Reporting**: Apache POI (Excel), iTextPDF (PDF)
- **Testing**: JUnit 4, MockK, Turbine, Google Truth

## Architecture

- **Offline-first**: Room is the single source of truth. Writes go to Room first, then async sync to Supabase
- **Push-then-pull sync**: Local changes pushed first, then remote changes pulled. Server-wins conflict resolution based on `updated_at` timestamps
- **Soft deletes**: Records marked with `is_deleted` flag, never hard-deleted
- **Sync engine**: Mutex-protected, debounced (2s), periodic (60s when pending ops exist), with retry logic and network-aware triggering

## Features

- **Worker Management** — Register workers with sequence numbers, names, phone numbers, and QR codes
- **Harvest Recording** — Log berry collection per worker/row with QR-based quick entry
- **Row Tracking** — Assign field rows to workers, track collection status by quarter with freshness expiration
- **Payments** — Record payments, view balances, daily summaries
- **Reporting** — Export harvest and payment data to Excel/PDF with date filtering
- **Sync Status** — Real-time sync indicator with offline mode support

## Data Model

| Entity | Purpose |
|--------|---------|
| Worker | Worker registry (name, phone, QR code) |
| Gather | Harvest records (worker, row, quantity, cost) |
| Row | Field rows with quarter, variety, collection status |
| Assignment | Worker-to-row mapping |
| PaymentRecord | Payment transactions |
| PaymentBalance | Cached worker balances |
| Settings | App config (punnet price) |

## Migration

This project was migrated from Realm + MongoDB Atlas Sync to Room + Supabase. The migration used an adapter pattern to enable incremental transition without downtime. See `DATA_LAYER_MIGRATION.md` for details.

## Testing

320+ tests covering the data layer, repositories, ViewModels, sync logic, and edge cases.

```
Unit tests:        273 (entities, DTOs, repositories, ViewModels, sync, validation)
Instrumented tests: 47 (DAOs, integration)
```

## Setup

1. Clone the repo
2. Copy `local.properties.example` to `local.properties`
3. Fill in your Supabase project URL and anon key (see [Supabase docs](https://supabase.com/docs/guides/api#api-url-and-keys))
4. Open in Android Studio, sync Gradle, run on device/emulator (minSdk 26)

## Documentation

- `docs/sync-architecture.md` — Sync engine design
- `DATA_LAYER_MIGRATION.md` — Realm → Room migration details
- `docs/UI_UX_REFACTOR_SPEC.md` — Planned UI improvements
- `audit/audit.md` — Code audit and recommendations

## License

Proprietary — source available for review, not for redistribution or commercial use.
