# ADR 0001: Modular transactional core

Status: accepted

Use one Spring MVC transactional runtime with separate pure domain modules. The
order, credit, approval, inventory and billing changes that share invariants stay
inside short PostgreSQL transactions. Full microservices and Event Sourcing were
rejected because they would turn these invariants into distributed coordination
without a demonstrated scaling need. Module boundaries are enforced by ArchUnit
and leave later extraction possible.
