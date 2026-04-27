# Power Ruble Org Contracts

Optional addon for [Power Ruble](https://github.com/PowerTronin/power-ruble).

This mod adds:
- player organizations with shared balances;
- organization roles and membership management;
- escrow-backed item delivery contracts for organizations.

It requires the base `power-ruble` mod on the server.

## Install

Put both jars into the server `mods` folder:

- `power-ruble-1.1.0.jar`
- `power-ruble-orgcontracts-1.1.0.jar`

## Commands

### Organizations

- `/org create <name>`
- `/org info`
- `/org members`
- `/org balance`
- `/org deposit <amount> [comment]`
- `/org pay <player> <amount> [comment]`
- `/org history`
- `/org invite <player>`
- `/org join`
- `/org leave`
- `/org kick <player>`
- `/org role <player> manager|member`

### Contracts

- `/contract create item <item> <count> <reward>`
- `/contract list`
- `/contract view <id>`
- `/contract accept <id>`
- `/contract deliver <id> <amount>`
- `/contract cancel <id>`
- `/contract history <id>`

### Admin

- `/orgcontracts reload`

## Contract rules

- reward is reserved immediately on contract creation;
- one contract can be accepted by one organization;
- delivery works by `item id` only, without NBT matching;
- delivery is partial;
- when progress reaches the target, escrow is paid to the organization balance;
- creator can cancel only `OPEN` contracts;
- operator can cancel `OPEN` and `ACCEPTED` contracts;
- on cancel, remaining escrow is refunded to the creator.

## Config

The addon creates `config/power-ruble-orgcontracts.json5`:

```json5
{
  organizations: {
    enabled: true,
    allowPlayerCreate: true,
    minNameLength: 3,
    maxNameLength: 24
  },
  contracts: {
    enabled: true,
    allowPlayerCreate: true,
    minReward: 1,
    maxReward: 1000000,
    maxOpenPerPlayer: 3,
    maxAcceptedPerOrganization: 5
  }
}
```

After editing config:
- restart the server, or
- run `/orgcontracts reload`

## Development note

This repository downloads the matching base development jar from the `power-ruble` release assets on demand:

- `https://github.com/PowerTronin/power-ruble/releases`

The version is controlled by:

- `base_mod_version` in `gradle.properties`

## Build

```sh
./gradlew build
```

Jar output:

```text
build/libs/power-ruble-orgcontracts-1.1.0.jar
```
