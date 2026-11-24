#!/usr/bin/env python3
"""Real SIGKILL fault matrix for the outbox -> Kafka -> consumer-inbox path.

Restricted to the disposable local qualification Compose projects. Database trigger
gates and erp-core's fault-injection gate make each crash window observable; fixtures
are event-specific, removed after each run and never part of a Flyway migration.
See tests/failure/README.md and the fault_matrix package next to this file.
"""
import sys

from fault_matrix.cli import main

if __name__ == '__main__':
    sys.exit(main())
