# Changelog

## [v0.3.0] - 2026-02-27

### Added
- **Contacts Sync**: Two-way synchronization between standard `.vcf` format files and Android System Contacts. 
- Deep integration with local-first file topologies for contacts. Deleting contacts natively manages the corresponding `.vcf` file securely.

### Fixed
- Stabilized Contact aggregation logic. Favouriting a contact or merging a contact inside the regular Android contact application will no longer accidentally delete its source `.vcf` file during the sync thanks to robust data hashing logic.
- Resolved an infinite spinning UI state issue where manual sync processes would get stuck permanently "pending" because of underlying Android `JobScheduler` limits. Applied `EXPEDITED` and `FORCE` flags with a strict timeout failsafe.
- General synchronization stability and crash fixes immediately after initial onboarding.
