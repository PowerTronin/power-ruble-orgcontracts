package ru.powerruble.orgcontracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

public final class ContractState extends PersistentState {
    private static final String STATE_KEY = PowerRubleOrgContractsMod.MOD_ID + "-contracts";
    private static final String NEXT_ID_KEY = "nextId";
    private static final String CONTRACTS_KEY = "contracts";
    private static final String ID_KEY = "id";
    private static final String ESCROW_ACCOUNT_ID_KEY = "escrowAccountId";
    private static final String CREATOR_ID_KEY = "creatorId";
    private static final String CREATOR_NAME_KEY = "creatorName";
    private static final String TYPE_KEY = "type";
    private static final String STATUS_KEY = "status";
    private static final String ITEM_ID_KEY = "itemId";
    private static final String REQUIRED_AMOUNT_KEY = "requiredAmount";
    private static final String DELIVERED_AMOUNT_KEY = "deliveredAmount";
    private static final String REWARD_KEY = "reward";
    private static final String CREATED_AT_KEY = "createdAt";
    private static final String ACCEPTED_ORGANIZATION_ID_KEY = "acceptedOrganizationId";
    private static final String ACCEPTED_ORGANIZATION_NAME_KEY = "acceptedOrganizationName";
    private static final String HISTORY_KEY = "history";

    private int nextId = 1;
    private final Map<Integer, Contract> contracts = new HashMap<>();

    public static ContractState get(MinecraftServer server) {
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(ContractState::fromNbt, ContractState::new, STATE_KEY);
    }

    public static ContractState fromNbt(NbtCompound nbt) {
        ContractState state = new ContractState();
        state.nextId = Math.max(1, nbt.getInt(NEXT_ID_KEY));
        if (!nbt.contains(CONTRACTS_KEY, NbtElement.LIST_TYPE)) {
            return state;
        }

        NbtList contractsData = nbt.getList(CONTRACTS_KEY, NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < contractsData.size(); index++) {
            Contract contract = Contract.fromNbt(contractsData.getCompound(index));
            if (contract == null) {
                continue;
            }

            state.contracts.put(contract.id(), contract);
            state.nextId = Math.max(state.nextId, contract.id() + 1);
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt(NEXT_ID_KEY, nextId);
        NbtList contractsData = new NbtList();
        contracts.values().stream()
            .sorted(Comparator.comparingInt(Contract::id))
            .forEach(contract -> contractsData.add(contract.toNbt()));
        nbt.put(CONTRACTS_KEY, contractsData);
        return nbt;
    }

    public Contract createItemDelivery(UUID creatorId, String creatorName, String itemId, long requiredAmount, long reward) {
        int contractId = nextId++;
        Contract contract = new Contract(
            contractId,
            UUID.randomUUID(),
            creatorId,
            creatorName,
            ContractType.ITEM_DELIVERY,
            ContractStatus.OPEN,
            itemId,
            requiredAmount,
            0L,
            reward,
            Instant.now(),
            Optional.empty(),
            "",
            List.of(Instant.now() + " created by " + creatorName + ": " + itemId + " x" + requiredAmount + " for " + reward)
        );
        contracts.put(contractId, contract);
        markDirty();
        return contract;
    }

    public Optional<Contract> getContract(int id) {
        return Optional.ofNullable(contracts.get(id));
    }

    public List<Contract> allContracts() {
        return contracts.values().stream()
            .sorted(Comparator.comparingInt(Contract::id))
            .toList();
    }

    public long openContractsForCreator(UUID creatorId) {
        return contracts.values().stream()
            .filter(contract -> contract.creatorId().equals(creatorId))
            .filter(contract -> contract.status() == ContractStatus.OPEN)
            .count();
    }

    public long acceptedContractsForOrganization(UUID organizationId) {
        return contracts.values().stream()
            .filter(contract -> contract.status() == ContractStatus.ACCEPTED)
            .filter(contract -> contract.acceptedOrganizationId().filter(organizationId::equals).isPresent())
            .count();
    }

    public boolean accept(int id, UUID organizationId, String organizationName, String actorName) {
        Contract current = contracts.get(id);
        if (current == null || current.status() != ContractStatus.OPEN) {
            return false;
        }

        contracts.put(id, current.withAcceptedOrganization(organizationId, organizationName, actorName));
        markDirty();
        return true;
    }

    public Optional<Contract> deliver(int id, long amount, String actorName) {
        Contract current = contracts.get(id);
        if (current == null || current.status() != ContractStatus.ACCEPTED || amount <= 0L) {
            return Optional.empty();
        }

        long nextDelivered = Math.min(current.requiredAmount(), current.deliveredAmount() + amount);
        Contract next = current.withDeliveredAmount(nextDelivered, actorName, amount);
        if (nextDelivered >= current.requiredAmount()) {
            next = next.complete();
        }

        contracts.put(id, next);
        markDirty();
        return Optional.of(next);
    }

    public Optional<Contract> cancel(int id, String actorName) {
        Contract current = contracts.get(id);
        if (current == null || (current.status() != ContractStatus.OPEN && current.status() != ContractStatus.ACCEPTED)) {
            return Optional.empty();
        }

        Contract cancelled = current.cancel(actorName);
        contracts.put(id, cancelled);
        markDirty();
        return Optional.of(cancelled);
    }

    public enum ContractType {
        ITEM_DELIVERY
    }

    public enum ContractStatus {
        OPEN,
        ACCEPTED,
        COMPLETED,
        CANCELLED
    }

    public record Contract(
        int id,
        UUID escrowAccountId,
        UUID creatorId,
        String creatorName,
        ContractType type,
        ContractStatus status,
        String itemId,
        long requiredAmount,
        long deliveredAmount,
        long reward,
        Instant createdAt,
        Optional<UUID> acceptedOrganizationId,
        String acceptedOrganizationName,
        List<String> history
    ) {
        public Contract withAcceptedOrganization(UUID organizationId, String organizationName, String actorName) {
            return new Contract(
                id,
                escrowAccountId,
                creatorId,
                creatorName,
                type,
                ContractStatus.ACCEPTED,
                itemId,
                requiredAmount,
                deliveredAmount,
                reward,
                createdAt,
                Optional.of(organizationId),
                organizationName,
                appendHistory("accepted by " + actorName + " for " + organizationName)
            );
        }

        public Contract withDeliveredAmount(long deliveredAmount, String actorName, long amount) {
            return new Contract(
                id,
                escrowAccountId,
                creatorId,
                creatorName,
                type,
                status,
                itemId,
                requiredAmount,
                deliveredAmount,
                reward,
                createdAt,
                acceptedOrganizationId,
                acceptedOrganizationName,
                appendHistory("delivered " + amount + " by " + actorName + " (" + deliveredAmount + "/" + requiredAmount + ")")
            );
        }

        public Contract complete() {
            return new Contract(
                id,
                escrowAccountId,
                creatorId,
                creatorName,
                type,
                ContractStatus.COMPLETED,
                itemId,
                requiredAmount,
                requiredAmount,
                reward,
                createdAt,
                acceptedOrganizationId,
                acceptedOrganizationName,
                appendHistory("completed; reward released to " + acceptedOrganizationName)
            );
        }

        public Contract cancel(String actorName) {
            return new Contract(
                id,
                escrowAccountId,
                creatorId,
                creatorName,
                type,
                ContractStatus.CANCELLED,
                itemId,
                requiredAmount,
                deliveredAmount,
                reward,
                createdAt,
                acceptedOrganizationId,
                acceptedOrganizationName,
                appendHistory("cancelled by " + actorName)
            );
        }

        public long remainingAmount() {
            return Math.max(0L, requiredAmount - deliveredAmount);
        }

        private List<String> appendHistory(String entry) {
            List<String> next = new ArrayList<>(history);
            next.add(0, Instant.now() + " " + entry);
            while (next.size() > 20) {
                next.remove(next.size() - 1);
            }
            return next;
        }

        private NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt(ID_KEY, id);
            nbt.putString(ESCROW_ACCOUNT_ID_KEY, escrowAccountId.toString());
            nbt.putString(CREATOR_ID_KEY, creatorId.toString());
            nbt.putString(CREATOR_NAME_KEY, creatorName);
            nbt.putString(TYPE_KEY, type.name());
            nbt.putString(STATUS_KEY, status.name());
            nbt.putString(ITEM_ID_KEY, itemId);
            nbt.putLong(REQUIRED_AMOUNT_KEY, requiredAmount);
            nbt.putLong(DELIVERED_AMOUNT_KEY, deliveredAmount);
            nbt.putLong(REWARD_KEY, reward);
            nbt.putString(CREATED_AT_KEY, createdAt.toString());
            acceptedOrganizationId.ifPresent(uuid -> nbt.putString(ACCEPTED_ORGANIZATION_ID_KEY, uuid.toString()));
            nbt.putString(ACCEPTED_ORGANIZATION_NAME_KEY, acceptedOrganizationName);
            NbtList historyData = new NbtList();
            history.forEach(entry -> historyData.add(NbtString.of(entry)));
            nbt.put(HISTORY_KEY, historyData);
            return nbt;
        }

        private static Contract fromNbt(NbtCompound nbt) {
            try {
                return new Contract(
                    nbt.getInt(ID_KEY),
                    UUID.fromString(nbt.getString(ESCROW_ACCOUNT_ID_KEY)),
                    UUID.fromString(nbt.getString(CREATOR_ID_KEY)),
                    nbt.getString(CREATOR_NAME_KEY),
                    ContractType.valueOf(nbt.getString(TYPE_KEY)),
                    ContractStatus.valueOf(nbt.getString(STATUS_KEY)),
                    nbt.getString(ITEM_ID_KEY),
                    nbt.getLong(REQUIRED_AMOUNT_KEY),
                    nbt.getLong(DELIVERED_AMOUNT_KEY),
                    nbt.getLong(REWARD_KEY),
                    Instant.parse(nbt.getString(CREATED_AT_KEY)),
                    readOptionalUuid(nbt, ACCEPTED_ORGANIZATION_ID_KEY),
                    nbt.getString(ACCEPTED_ORGANIZATION_NAME_KEY),
                    readHistory(nbt)
                );
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private static Optional<UUID> readOptionalUuid(NbtCompound nbt, String key) {
            String value = nbt.getString(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }

            try {
                return Optional.of(UUID.fromString(value));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        private static List<String> readHistory(NbtCompound nbt) {
            List<String> history = new ArrayList<>();
            if (!nbt.contains(HISTORY_KEY, NbtElement.LIST_TYPE)) {
                return history;
            }

            NbtList historyData = nbt.getList(HISTORY_KEY, NbtElement.STRING_TYPE);
            for (int index = 0; index < historyData.size(); index++) {
                history.add(historyData.getString(index));
            }
            return history;
        }
    }
}
