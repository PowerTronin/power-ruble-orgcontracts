package ru.powerruble.orgcontracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

public final class OrganizationState extends PersistentState {
    private static final String STATE_KEY = PowerRubleOrgContractsMod.MOD_ID + "-organizations";
    private static final String ORGANIZATIONS_KEY = "organizations";
    private static final String ORG_ID_KEY = "id";
    private static final String ORG_NAME_KEY = "name";
    private static final String ORG_OWNER_KEY = "owner";
    private static final String ORG_CREATED_AT_KEY = "createdAt";
    private static final String ORG_MEMBERS_KEY = "members";
    private static final String MEMBER_ID_KEY = "id";
    private static final String MEMBER_NAME_KEY = "name";
    private static final String MEMBER_ROLE_KEY = "role";

    private final Map<UUID, Organization> organizations = new HashMap<>();
    private final Map<UUID, UUID> membership = new HashMap<>();

    public static OrganizationState get(MinecraftServer server) {
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(OrganizationState::fromNbt, OrganizationState::new, STATE_KEY);
    }

    public static OrganizationState fromNbt(NbtCompound nbt) {
        OrganizationState state = new OrganizationState();
        if (!nbt.contains(ORGANIZATIONS_KEY, NbtElement.LIST_TYPE)) {
            return state;
        }

        NbtList organizationsData = nbt.getList(ORGANIZATIONS_KEY, NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < organizationsData.size(); index++) {
            Organization organization = Organization.fromNbt(organizationsData.getCompound(index));
            if (organization == null) {
                continue;
            }

            state.organizations.put(organization.id(), organization);
            for (Member member : organization.members().values()) {
                state.membership.put(member.id(), organization.id());
            }
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList organizationsData = new NbtList();
        organizations.values().stream()
            .sorted(Comparator.comparing(Organization::name, String.CASE_INSENSITIVE_ORDER))
            .forEach(organization -> organizationsData.add(organization.toNbt()));
        nbt.put(ORGANIZATIONS_KEY, organizationsData);
        return nbt;
    }

    public boolean hasOrganization(UUID playerId) {
        return membership.containsKey(playerId);
    }

    public Optional<Organization> organizationFor(UUID playerId) {
        UUID organizationId = membership.get(playerId);
        if (organizationId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(organizations.get(organizationId));
    }

    public Optional<Organization> organizationByName(String rawName) {
        String normalized = normalizeName(rawName);
        return organizations.values().stream()
            .filter(organization -> normalizeName(organization.name()).equals(normalized))
            .findFirst();
    }

    public Optional<Organization> createOrganization(UUID ownerId, String ownerName, String name) {
        if (hasOrganization(ownerId) || organizationByName(name).isPresent()) {
            return Optional.empty();
        }

        UUID organizationId = UUID.randomUUID();
        Map<UUID, Member> members = new HashMap<>();
        members.put(ownerId, new Member(ownerId, ownerName, OrgRole.OWNER));

        Organization organization = new Organization(
            organizationId,
            name,
            ownerId,
            Instant.now(),
            members
        );

        organizations.put(organizationId, organization);
        membership.put(ownerId, organizationId);
        markDirty();
        return Optional.of(organization);
    }

    public boolean addMember(UUID organizationId, UUID playerId, String playerName, OrgRole role) {
        Organization organization = organizations.get(organizationId);
        if (organization == null || membership.containsKey(playerId) || organization.members().containsKey(playerId)) {
            return false;
        }

        Map<UUID, Member> nextMembers = new HashMap<>(organization.members());
        nextMembers.put(playerId, new Member(playerId, playerName, role));
        organizations.put(organizationId, organization.withMembers(nextMembers));
        membership.put(playerId, organizationId);
        markDirty();
        return true;
    }

    public boolean removeMember(UUID organizationId, UUID playerId) {
        Organization organization = organizations.get(organizationId);
        if (organization == null || !organization.members().containsKey(playerId) || organization.ownerId().equals(playerId)) {
            return false;
        }

        Map<UUID, Member> nextMembers = new HashMap<>(organization.members());
        nextMembers.remove(playerId);
        organizations.put(organizationId, organization.withMembers(nextMembers));
        membership.remove(playerId);
        markDirty();
        return true;
    }

    public boolean setRole(UUID organizationId, UUID playerId, OrgRole role) {
        Organization organization = organizations.get(organizationId);
        if (organization == null || !organization.members().containsKey(playerId) || organization.ownerId().equals(playerId)) {
            return false;
        }

        Map<UUID, Member> nextMembers = new HashMap<>(organization.members());
        Member member = nextMembers.get(playerId);
        nextMembers.put(playerId, new Member(member.id(), member.name(), role));
        organizations.put(organizationId, organization.withMembers(nextMembers));
        markDirty();
        return true;
    }

    public boolean deleteOrganization(UUID organizationId) {
        Organization removed = organizations.remove(organizationId);
        if (removed == null) {
            return false;
        }

        for (Member member : removed.members().values()) {
            membership.remove(member.id());
        }

        markDirty();
        return true;
    }

    public static String normalizeName(String rawName) {
        return rawName.trim().toLowerCase(Locale.ROOT);
    }

    public record Member(UUID id, String name, OrgRole role) {
        private NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString(MEMBER_ID_KEY, id.toString());
            nbt.putString(MEMBER_NAME_KEY, name);
            nbt.putString(MEMBER_ROLE_KEY, role.name());
            return nbt;
        }

        private static Member fromNbt(NbtCompound nbt) {
            try {
                UUID id = UUID.fromString(nbt.getString(MEMBER_ID_KEY));
                String name = nbt.getString(MEMBER_NAME_KEY);
                OrgRole role = OrgRole.valueOf(nbt.getString(MEMBER_ROLE_KEY));
                return new Member(id, name, role);
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }

    public record Organization(
        UUID id,
        String name,
        UUID ownerId,
        Instant createdAt,
        Map<UUID, Member> members
    ) {
        public Organization withMembers(Map<UUID, Member> members) {
            return new Organization(id, name, ownerId, createdAt, members);
        }

        public List<Member> sortedMembers() {
            List<Member> sorted = new ArrayList<>(members.values());
            sorted.sort(
                Comparator.comparing((Member member) -> member.role().ordinal())
                    .thenComparing(Member::name, String.CASE_INSENSITIVE_ORDER)
            );
            return sorted;
        }

        private NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString(ORG_ID_KEY, id.toString());
            nbt.putString(ORG_NAME_KEY, name);
            nbt.putString(ORG_OWNER_KEY, ownerId.toString());
            nbt.putString(ORG_CREATED_AT_KEY, createdAt.toString());

            NbtList membersData = new NbtList();
            sortedMembers().forEach(member -> membersData.add(member.toNbt()));
            nbt.put(ORG_MEMBERS_KEY, membersData);
            return nbt;
        }

        private static Organization fromNbt(NbtCompound nbt) {
            try {
                UUID id = UUID.fromString(nbt.getString(ORG_ID_KEY));
                String name = nbt.getString(ORG_NAME_KEY);
                UUID ownerId = UUID.fromString(nbt.getString(ORG_OWNER_KEY));
                Instant createdAt = Instant.parse(nbt.getString(ORG_CREATED_AT_KEY));
                NbtList membersData = nbt.getList(ORG_MEMBERS_KEY, NbtElement.COMPOUND_TYPE);
                Map<UUID, Member> members = new HashMap<>();
                for (int index = 0; index < membersData.size(); index++) {
                    Member member = Member.fromNbt(membersData.getCompound(index));
                    if (member != null) {
                        members.put(member.id(), member);
                    }
                }

                if (!members.containsKey(ownerId)) {
                    return null;
                }

                return new Organization(id, name, ownerId, createdAt, members);
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }
}
