# Step 7 — Department Service

## 1. Goal

Build organizational structure: department CRUD, a self-referencing
hierarchy (departments contain sub-departments), and statistics —
owning `department_db` exclusively. This is the first service to model a
tree inside a relational table and to confront the two classic hazards of
self-referencing data: cycles (a department accidentally becoming its own
ancestor) and unsafe deletes (removing a node that still has children).

Deliberately **out of scope for this step**: employee headcount
statistics. That requires data Department Service doesn't own (it lives
in Employee Service's database), and computing it correctly — live
cross-service call vs. an eventually-consistent local cache — is the
actual subject of Steps 8-9. Building it here, ahead of those lessons,
would mean either a rushed, undocumented Feign call or a stats column
that's permanently wrong. What ships in this step is everything
Department Service can answer truthfully using only its own data today.

## 2. Architecture Diagram

```
                              API Gateway (verifies JWT, adds X-Auth-*)
                                             │
                                             ▼
                                   Department Service
   ┌──────────────────────────────────────────────────────────────────┐
   │  HeaderAuthenticationFilter (trusts X-Auth-*, same as Steps 5-6)     │
   │                          │                                          │
   │                  DepartmentController                                │
   │        CRUD · /children · /hierarchy · /ancestors · /statistics       │
   │                          │                                              │
   │                  DepartmentServiceImpl                                   │
   │   - validateNoCycle()   before any reparent                              │
   │   - countByParentDepartmentId() > 0  blocks delete                        │
   │   - buildTree() / getAncestors() — plain repository queries,               │
   │     not JPA-relationship traversal                                          │
   │                          │                                                    │
   │                          ▼                                                     │
   │                    department_db (MySQL)                                        │
   │              departments (self-referencing parent_department_id)                 │
   └────────────────────────────────────────────────────────────────────────────────┘

Example tree stored entirely with plain parent-pointer columns:

   Engineering (root, parent_department_id = NULL)
   ├── Platform        (parent_department_id = Engineering.id)
   │   └── SRE          (parent_department_id = Platform.id)
   └── Product          (parent_department_id = Engineering.id)
```

## 3. Folder Structure

```
department-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/enterprise/ems/department/
    │   │   ├── DepartmentServiceApplication.java
    │   │   ├── domain/       # Department (plain parentDepartmentId column)
    │   │   ├── repository/   # findByParentDepartmentId, countByParentDepartmentId, ...
    │   │   ├── dto/          # Request/Response, DepartmentTreeNode, DepartmentStatisticsResponse
    │   │   ├── mapper/       # MapStruct DepartmentMapper
    │   │   ├── service/impl/ # DepartmentServiceImpl (hierarchy + cycle logic)
    │   │   ├── controller/   # DepartmentController
    │   │   ├── security/     # HeaderAuthenticationFilter (duplicated again, by design)
    │   │   ├── exception/    # incl. CircularHierarchyException, DepartmentHasChildrenException
    │   │   └── config/       # SecurityConfig, OpenApiConfig
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/ # V1 departments (self-referencing FK)
    └── test/
        ├── java/com/enterprise/ems/department/
        │   ├── service/       # 14 unit tests, mostly hierarchy edge cases
        │   ├── controller/    # @WebMvcTest slice
        │   └── integration/   # H2 full-context + Testcontainers MySQL
        └── resources/application-test.yml
```

## 4. Explanation

### Plain parent-pointer column, not a JPA `@ManyToOne` self-relationship

`Department.parentDepartmentId` is a `Long`, not a lazy-loaded reference
to another `Department` entity. Hierarchy traversal (subtree, ancestor
chain) is written explicitly in `DepartmentServiceImpl` using direct
repository queries (`findByParentDepartmentId`) rather than walking an
object graph. For a tree of arbitrary, runtime-determined depth, explicit
traversal is easier to reason about, easier to unit test with plain
Mockito stubs (no Hibernate proxies to fight), and makes the O(depth) or
O(subtree size) cost of each operation visible in the code rather than
hidden behind property access.

### Preventing cycles before they're written

A self-referencing tree has exactly one integrity rule a relational
database's foreign key constraint cannot enforce on its own: **a
department must never become its own ancestor.** `validateNoCycle` walks
from the *proposed* parent up toward the root; if the department being
updated appears anywhere in that walk, the department already sits above
the proposed parent in the tree, and accepting the change would create a
cycle. Three cases matter and are each covered by a dedicated test:
- Direct self-parenting (`department.parent = itself`).
- Parenting under a direct child (one hop).
- Parenting under a grandchild or deeper descendant (multiple hops) —
  the case a naive "check only the immediate parent" implementation
  would miss entirely.

### Preventing unsafe deletes

`DELETE /departments/{id}` fails with 409 if `countByParentDepartmentId(id) > 0`.
The alternative — cascading the delete through the whole subtree, or
silently orphaning children by nulling their `parent_department_id` — both
destroy information a caller almost certainly didn't intend to destroy.
Making the caller explicitly reparent or delete children first is a
deliberately conservative default; the database's own foreign key
(`ON DELETE` with no cascade clause, which defaults to `RESTRICT` in
MySQL) backs this up as defense in depth, in case the application-layer
check is ever bypassed.

### Statistics: what's honest to compute today

`DepartmentStatisticsResponse` reports `directChildrenCount`,
`totalDescendantCount`, and `depthFromRoot` — all derivable purely from
this service's own table. It has no headcount field. Adding one now,
before Step 8 gives Department Service a way to actually ask Employee
Service (via OpenFeign, with a documented resilience story), would mean
either blocking every statistics request on Employee Service being up, or
shipping a field that's always zero. Neither is honest. Step 9 goes
further and shows how to keep such a field correct *without* a live call
per request, using an event-driven local read model.

## 5. Implementation

Module: `department-service/`, added to the root `pom.xml` `<modules>`.

- **`V1__create_departments_table.sql`** — self-referencing foreign key
  (`parent_department_id → departments.id`), unique constraints on both
  `name` and `code`, an index on `parent_department_id` (every hierarchy
  query filters by it).
- **`DepartmentServiceImpl`** — CRUD with duplicate name/code checks
  (create and update, update excluding the record's own id), the cycle
  guard described above, delete-blocked-by-children, subtree building
  (`buildTree`, recursive), ancestor-chain walking (`getAncestors`,
  root-first order), and statistics.
- **`DepartmentMapper.updateEntityFromRequest`** — deliberately does
  **not** use MapStruct's null-ignoring strategy (unlike Employee
  Service's equivalent method): `parentDepartmentId` and
  `managerEmployeeId` are legitimately nullable, and a `PUT` is a
  full-replace operation, so a client omitting one of these fields must
  be able to clear it rather than have the previous value silently
  preserved. Caught and fixed while writing this service, before it ever
  reached a test.
- **`HeaderAuthenticationFilter`** / **`SecurityConfig`** — same
  trusted-header RBAC pattern as Steps 5-6 (read open to any
  authenticated caller, writes restricted to ADMIN/MANAGER).

## 6. Testing

**26 tests, all passing** (25 executed, 1 self-skipped without Docker):

- `DepartmentServiceImplTest` (14) — create/duplicate name/duplicate
  code/missing parent, not-found on get, **three distinct cycle
  scenarios** (self-parent, parent-is-own-child, parent-is-own-grandchild)
  plus a valid reparenting that must succeed, delete blocked/allowed by
  child count, subtree construction across three levels, ancestor-chain
  ordering, and statistics counts across a small tree.
- `DepartmentControllerTest` (5, `@WebMvcTest`) — status codes and
  validation (including the department code's pattern constraint).
- `DepartmentServiceApplicationIntegrationTest` (6, full context, H2 in
  MySQL-compatibility mode) — full CRUD lifecycle over real HTTP,
  duplicate-name 409, a real three-level tree exercised through
  `/hierarchy`, `/ancestors`, and `/statistics` simultaneously, a rejected
  reparent-under-descendant over HTTP (400), a rejected delete-with-children
  over HTTP (409), and all three RBAC cases.
- `MySqlIntegrationTest` (1, `@Testcontainers(disabledWithoutDocker = true)`) —
  re-validates the self-referencing foreign key against real MySQL;
  self-skips here for the same reason as the previous two services'
  equivalent classes.

```
mvn -pl department-service -am verify
```

## 7. Interview Questions

1. **How do you prevent a cycle in a self-referencing hierarchy table,
   and why isn't checking only the immediate parent enough?**
   *Look for:* walking the full ancestor chain of the proposed parent;
   a department could be several levels removed from a descendant, and
   checking only one hop misses that case entirely.

2. **What are the three options for handling `DELETE` on a node with
   children in a tree structure, and what did this service choose?**
   *Look for:* cascade delete the subtree, orphan the children (null
   their parent pointer), or reject the delete outright — this service
   rejects, on the reasoning that silently destroying or restructuring
   data a caller didn't ask to touch is worse than asking them to be
   explicit.

3. **Why model `parentDepartmentId` as a plain column instead of a JPA
   `@ManyToOne` self-relationship, given the parent genuinely lives in
   the same table?**
   *Look for:* explicit traversal is easier to test, easier to reason
   about the cost of (O(depth)/O(subtree)) for an arbitrary-depth tree,
   and avoids lazy-loading/proxy surprises during recursive walks.

4. **Why doesn't this service expose an employee headcount statistic
   yet, even though "department statistics" was a stated requirement?**
   *Look for:* the data isn't owned here; a live call needs a resilience
   story (Step 8) and a cache needs an event source (Step 9) — shipping
   it prematurely means either a fragile live dependency or a
   permanently-wrong number.

5. **What database-level protection backs up the application-layer
   "can't delete a department with children" check?**
   *Look for:* the self-referencing foreign key's default `ON DELETE`
   behavior (`RESTRICT` in MySQL) — defense in depth in case the
   application check is ever bypassed (a direct SQL script, a bug, a
   different code path).

## 8. Best Practices

- For any self-referencing hierarchy, write and test cycle prevention
  before anything else touches the parent pointer — it is the one
  invariant a foreign key constraint alone cannot enforce.
- Default to rejecting destructive structural changes (delete-with-children)
  rather than guessing the caller's intent.
- Don't expose a field you cannot yet compute correctly — an honestly
  smaller API beats a dishonestly complete one.
- Re-verify `@BeanMapping` null-handling strategy choices per DTO: what's
  correct for a DTO with all-required fields (Employee Service) can be
  wrong for one with legitimately-nullable fields (this service).

## 9. Common Mistakes

- Checking only the immediate parent for cycles and missing
  multi-level cases.
- Cascading deletes through a tree "for convenience" and later
  discovering it destroyed far more than the caller intended.
- Copy-pasting a `@BeanMapping(nullValuePropertyMappingStrategy = IGNORE)`
  from another service's mapper without checking whether this DTO's
  nullable fields need to support being explicitly cleared.
- Adding a cross-service statistic before the course has covered how to
  call another service safely (timeouts, retries, fallbacks) — resulting
  in an endpoint that hangs or 500s the moment the other service is slow
  or down.

## 10. Summary

Department Service now models a real organizational hierarchy safely —
no cycles, no accidental data loss on delete — using nothing but its own
database, with statistics limited to what it can compute honestly today.

**Next step (Step 8): Inter-service communication** — OpenFeign and
WebClient, plus Resilience4j (circuit breaker, retry, bulkhead, rate
limiter, timeout, fallback). This is where Employee Service starts
calling Department Service to validate a `departmentId` at write time,
and where Department Service could — now with a proper resilience story —
finally ask Employee Service for a live headcount.
