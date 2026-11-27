# notification-service 1.3.1

1.3.1 builds on 1.3.0: everything in the [1.3.0 release notes](./RELEASE_NOTES_1.3.0.md) applies.
It adds one read to the push device registry (DD-37): the list of a person's devices.
Nothing behaves differently for an application that uses the shipped registries.

## Highlights

- **List a person's devices.**
  `PushDeviceRegistry.devices(userId)` returns every device of the user in the current tenant scope, of every app, active and stale alike, most recently seen first.
  A host can show a person the devices signed in to their account and let them remove one with `unregister(userId, installationId)`.
  Scope follows `TenantContext`, as for every other registry operation; an unknown user has no devices.
- **One staleness rule.**
  `PushDevice.isActive(staleAfter, now)` tells which listed devices notifications reach, with the same comparison `activeDevices` applies: a device seen at or after `now - staleAfter` is active.
  Pass `notification.push.devices.stale-after` as `staleAfter`.
- **Tokens stay inside the trust boundary.**
  The listed devices carry their push token, as `activeDevices` does.
  Never show `PushDevice.token()` to an end user or write it to a log; show the installation id, the platform, the app, the last-seen time or `tokenHash()` instead.

## Behaviour changes

None.

## API additions and compatibility

- `PushDeviceRegistry` gains the abstract method `List<PushDevice> devices(String userId)`.
  **Implementers of `PushDeviceRegistry` must add `devices(userId)`**: an application that declares its own registry bean does not compile against 1.3.1 until it does.
  `InMemoryPushDeviceRegistry` and `JdbcPushDeviceRegistry` implement it; the JDBC registry, like `activeDevices`, leaves out expired devices that wait for the purger.
- `PushDevice` gains `boolean isActive(Duration staleAfter, Instant now)`.
- `PushDeviceRegistryContractTest` (notification-core test-jar) covers `devices`: ordering, stale devices, tenant scopes and unknown users.
  A registry of your own that runs the contract test gets these cases too.

No schema change: `devices` reads the `<prefix>push_device` table of 1.2.3 as it is.

## Design

[DD-37: Push device registry, user targeting, token cleanup and push templates](./design-decisions/37-push-device-registry-and-user-targeting.md).
