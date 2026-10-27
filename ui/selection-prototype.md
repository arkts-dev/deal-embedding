# Experimental selection composition

This is an isolated architecture probe, not a registered generation route or a replacement for the maintained contract.

A composition contains exactly `title`, `inputs`, `resources`, `selection`, `evaluation`.

- `inputs`: editable values, each `{id,label,type,value,minimum,maximum}`. Types: integer, money (minor units), time (minute of day), text. Numeric bounds are inclusive whole units. Text bounds are unused. References use `$id`.
- `resources`: ordered capability reads, each `{id,binding:{module,function},arguments:[formula,...]}`. Bind only disclosed declarations. Results and their complete types are inferred. A later read may reference an earlier result as `$id`. No handlers, loading flags or record declarations are supplied.
- `selection`: `{source,id,label,cost,capacity,eligible,budget,quotas,components}`. Source names an array resource (`$id`). `id` and `label` are row field paths, not formulas. Other scalar properties are formulas evaluated for each source `$row`. Capacity is the maximum integral quantity of each row; zero excludes it. Cost and budget govern local selection enumeration only. For provider-authoritative evaluation without an authoritative local price bound, use zero local cost and budget instead of pruning with guessed prices.
- `quotas`: any number of `{minimum,maximum,predicate}`. Predicate is boolean on a source `$row`; each unit contributes one when true. These are additive category constraints, not exclusive role assignment: one unit can contribute to several categories. Bounds are inclusive integers, formulas on inputs/resources. At least one quota is required. Use an all-true predicate with equal minimum/maximum for an exact total quantity. Enumeration includes every feasible quantity vector; it does not silently optimize or choose a provider answer.
- `components`: `{path,id,quantity}` maps a row's component array, referenced component ID and integral count. Use empty strings for all three when absent. A component-containing row contributes the sum of its components' predicate matches instead of its own direct predicates. Only one component level is supported. Shared stock consumption, malformed/unknown references and duplicate item IDs are checked by maintained code.
- `evaluation`: `{binding,arguments,cost,eligible,ttlMillis}`. A nonempty binding is an authoritative call for each candidate; use `{}` and `[]` for local evaluation. Cost/eligibility/TTL formulas reference `$reply` and `$candidate` (candidate has `items,cost`). TTL is a positive duration in milliseconds from each reply receipt. Local evaluation may reference resources and candidate cost. No request epochs, completion handlers, expiry timestamps or result views are supplied.
- Basket arguments are `{basket:"$candidate.items",encoding:"idQuantity",idField:"",quantityField:""}` for the provider's explicit comma-separated id:quantity format, or `{basket:"$candidate.items",encoding:"records",idField:"<provider key>",quantityField:"<provider quantity>"}` for a typed array of two-field records. The serializer omits zero quantities. Other arguments are formulas.

Formulas are JSON objects or references, never source strings:

- `"$input"`, `"$resource"`, `"$row.field"`, `"$candidate.cost"`, `"$reply.field"`.
- Literal: `{"value": <string|number|boolean>}`. Plain strings always mean references.
- Operator: `{"op": "<operator>", "args": [formula,...]}`.
- Field of a computed record: `{"field":"fieldName","of":formula}`.
- Operators: add, subtract, multiply; eq, ne, ge, le, gt, lt; and, or, not; choose(condition,yes,no); clock(minute-of-day); integer(number); filter(array,boolean on `$row`); sum(array,numeric on `$row`); all/any(array,predicate); length(array); first(array).
- `first` has a default record when empty; guard eligibility explicitly when absence matters. It is not evidence that a choice exists.

Maintained code owns input state, reads, pending/error handling, invalidation, finite enumeration, basket transport, provider result collection, per-reply expiry and standard output. No custom source or state/action/node/view graph is accepted here.

Limits: this probe does not implement row quantity editing, dependent-resource caching, alternative-option optimization, nested bundle expansion, exclusive role assignment or full result presentation. Exhaustive enumeration can grow combinatorially; operational failure is not infeasibility. Passing this slice cannot establish complete application delivery.
