# RWC Waste Tracking System

## Project

This system is being developed for Resource Work Cooperative to replace selected paper and Excel-based waste tracking workflows.

## Technology Stack

- Frontend: React with TypeScript
- Backend: Java with Spring Boot
- Database: PostgreSQL
- Database migrations: Flyway
- Build tool: Maven
- Version control: Git

## Development Principles

- Keep the architecture simple.
- Do not introduce new frameworks or infrastructure without approval.
- Do not invent business requirements.
- Follow the approved SRS.
- Prefer readable code over clever abstractions.
- Avoid microservices.
- Avoid unnecessary design patterns.
- Use REST APIs.
- Use DTOs between API and frontend.
- Use BigDecimal for weights and percentages.
- Never use float or double for stored waste weights.
- Validate percentage totals where required.
- Database changes must use Flyway migrations.
- Do not hardcode passwords or secrets.

## User Access

There are two user roles.

### Staff

- General operational staff use one shared Staff account.
- Staff can create records.
- Staff can review and correct data before submission.
- Staff cannot edit or delete submitted records.

### Manager/Admin

- Each Manager/Admin has an individual account.
- Admins can create records.
- Admins can view all records.
- Admins can edit and delete submitted records.
- Administrative modifications should be traceable to the individual Admin account.

## Initial Workflows

- Crate / Pallet
- Salvage
- Electrical Crate / Pallet
- Pickup
- Whitegoods
- Collectables
- Electrical Testing
- E-waste Pickup
- Scrap

Some E-waste Pickup and Scrap reporting rules remain pending clarification.

Do not invent missing rules.

## Current Development Priority

Build one workflow end-to-end before implementing the others.

The first workflow is Crate / Pallet.

It must support:

- date
- container type
- gross weight
- automatic tare deduction
- automatic net weight calculation
- material percentage breakdown
- validation that percentages total 100%
- calculated weight for each material category
- review before submission
- saving the record
- displaying the saved record on the Records page

Applied Flyway migrations are immutable.
Any schema change must use a new migration version.
Never modify an already-applied migration.