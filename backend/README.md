# backend

The main application: Spring Boot 3.5, Java 21, Maven, MySQL with Liquibase. Phase 1 contains login (JWT), contracts (create, list, get) and human review. See the root README for how to run it, curl examples and known limitations.

Packages under `com.contractiq`: `auth`, `organization`, `user`, `contract` and `common` (config, errors, security). Database changes go only through the XML changelogs in `src/main/resources/db/changelog/`.
