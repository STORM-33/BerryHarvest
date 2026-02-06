# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BerryHarvest is an Android app (Kotlin) for managing berry farm operations — worker management, row assignments, harvest gathering, payments, and reporting. The UI is in Ukrainian.

## Build & Test Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing config)
./gradlew assembleRelease

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.example.berryharvest.data.repository.room.RoomWorkerRepositoryTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.example.berryharvest.data.repository.room.RoomWorkerRepositoryTest.addWorker_success"

# Run Android instrumented tests (requires emulator/device)
./gradlew connectedDebugAndroidTest

# Clean build
./gradlew clean
```

## Architecture

**Pattern:** MVVM with offline-first sync. Local Room database is the single source of truth; writes go to Room first, then sync asynchronously to Supabase (PostgreSQL).

```
Compose Screens → ViewModels (StateFlow) → Repositories → Room DB ↔ SyncManager → Supabase
```

### Data Layer

- **Models** (`data/model/`): Pure Kotlin data classes (Worker, Gather, Row, Assignment, PaymentRecord, PaymentBalance, Settings)
- **Room entities** (`data/local/entity/`): Database entities with sync columns (`synced_at`, `server_updated_at`, `is_deleted`)
- **DAOs** (`data/local/dao/`): Room data access objects
- **Database** (`data/local/BerryHarvestDatabase.kt`): Room database v7 with migrations
- **Remote DTOs** (`data/remote/dto/`): Supabase data transfer objects with `toEntity()`/`fromEntity()` mappers
- **Remote data sources** (`data/remote/datasource/`): Supabase CRUD operations per entity
- **Repositories** (`data/repository/`): Interface-based. Original interfaces in `data/repository/`, Room implementations in `data/repository/room/`
- **Adapter layer** (`data/repository/room/Adapted*.kt`): Bridges original repository interfaces to Room implementations

### Sync System

- **SyncManager** (`data/sync/SyncManager.kt`): Push-then-pull sync with mutex protection, retry logic (3 attempts, exponential backoff), 30s timeout, 10s minimum interval between syncs
- **Conflict resolution:** Server-wins based on `updated_at` timestamps
- **Unsynced tracking:** Records with `synced_at = NULL` are pending sync
- **Soft deletes:** All entities use `is_deleted` flag instead of hard deletes

### UI Layer

- **Compose screens** (`ui/screens/`): GatherScreen, AssignRowsScreen, WorkersScreen, PaymentScreen, ReportScreen, RowCollectionScreen
- **Navigation** (`ui/navigation/`): Compose Navigation with ModalNavigationDrawer
- **Components** (`ui/components/`): Reusable composables (ConfirmDialog, EmptyState, LoadingOverlay, SearchableWorkerDropdown)
- **Theme** (`ui/theme/`): Material3 with Color.kt, Theme.kt, Type.kt
- **ViewModels** live alongside their legacy fragment packages (e.g., `ui/gather/GatherViewModel.kt`)

### Key Infrastructure

- **BerryHarvestApplication**: Initializes database, repositories, SyncManager, NetworkConnectivityManager
- **ViewModelFactory** (`util/ViewModelFactory.kt`): Manual DI — no Hilt/Dagger
- **RowExpirationManager**: Periodic checks for expired row collection statuses
- **NetworkConnectivityManager**: ConnectivityManager callbacks → StateFlow<ConnectionState>

## Configuration

- Supabase credentials loaded from `local.properties` (`supabase.url`, `supabase.anon.key`) into BuildConfig
- Supabase schema migrations in `supabase/migrations/`
- ProGuard rules preserve Apache POI, ZXing, and data model classes

## Testing

- **Unit tests** (`app/src/test/`): 154+ tests using JUnit 4, MockK, Turbine (Flow testing), Truth assertions
- **Instrumented tests** (`app/src/androidTest/`): DAO and repository integration tests
- Tests require `--add-opens` JVM args (configured in build.gradle.kts) for MockK reflection
- `unitTests.isReturnDefaultValues = true` is set for Android framework stubs

## Key Technical Details

- **Kotlin 1.9.0**, JVM target 17, compileSdk/targetSdk 35, minSdk 26
- **Compose BOM 2024.12.01** with Kotlin compiler extension 1.5.1
- **Room 2.6.1** with KSP compiler
- **Supabase SDK 2.6.1** (postgrest-kt, realtime-kt) over Ktor
- **No dependency injection framework** — manual construction in Application class
- **Apache POI 5.2.3** for Excel report generation, **iTextPDF 5.5.13.3** for PDF
- **ZXing** for QR code scanning (CaptureActivity from journeyapps)
- `largeHeap=true` in manifest for memory-intensive operations

## Migration Context

The app was migrated from XML/Fragments + Realm to Compose + Room + Supabase. Some legacy XML layout files and Fragment code have been deleted but legacy ViewModels remain in their original packages. See `REFACTOR.md` and `DATA_LAYER_MIGRATION.md` for migration details. See `docs/sync-architecture.md` for sync design.
