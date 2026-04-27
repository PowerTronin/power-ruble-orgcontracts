package ru.powerruble.orgcontracts;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.api.SyntaxError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class OrgContractsConfig {
    private static final String CONFIG_FILE_NAME = PowerRubleOrgContractsMod.MOD_ID + ".json5";

    private final boolean organizationsEnabled;
    private final boolean allowPlayerOrganizationCreate;
    private final int minOrganizationNameLength;
    private final int maxOrganizationNameLength;
    private final boolean contractsEnabled;
    private final boolean allowPlayerContractCreate;
    private final long minContractReward;
    private final long maxContractReward;
    private final int maxOpenContractsPerPlayer;
    private final int maxAcceptedContractsPerOrganization;

    private OrgContractsConfig(
        boolean organizationsEnabled,
        boolean allowPlayerOrganizationCreate,
        int minOrganizationNameLength,
        int maxOrganizationNameLength,
        boolean contractsEnabled,
        boolean allowPlayerContractCreate,
        long minContractReward,
        long maxContractReward,
        int maxOpenContractsPerPlayer,
        int maxAcceptedContractsPerOrganization
    ) {
        this.organizationsEnabled = organizationsEnabled;
        this.allowPlayerOrganizationCreate = allowPlayerOrganizationCreate;
        this.minOrganizationNameLength = minOrganizationNameLength;
        this.maxOrganizationNameLength = maxOrganizationNameLength;
        this.contractsEnabled = contractsEnabled;
        this.allowPlayerContractCreate = allowPlayerContractCreate;
        this.minContractReward = minContractReward;
        this.maxContractReward = maxContractReward;
        this.maxOpenContractsPerPlayer = maxOpenContractsPerPlayer;
        this.maxAcceptedContractsPerOrganization = maxAcceptedContractsPerOrganization;
    }

    public static OrgContractsConfig load() {
        return load(FabricLoader.getInstance().getConfigDir());
    }

    static OrgContractsConfig load(Path configDir) {
        Path path = configDir.resolve(CONFIG_FILE_NAME);
        if (Files.exists(path)) {
            OrgContractsConfig config = readJson5(path);
            if (config != null) {
                return config;
            }
        }

        OrgContractsConfig defaults = defaults();
        writeJson5(path, defaults);
        return defaults;
    }

    public boolean organizationsEnabled() {
        return organizationsEnabled;
    }

    public boolean allowPlayerOrganizationCreate() {
        return allowPlayerOrganizationCreate;
    }

    public int minOrganizationNameLength() {
        return minOrganizationNameLength;
    }

    public int maxOrganizationNameLength() {
        return maxOrganizationNameLength;
    }

    public boolean contractsEnabled() {
        return contractsEnabled;
    }

    public boolean allowPlayerContractCreate() {
        return allowPlayerContractCreate;
    }

    public long minContractReward() {
        return minContractReward;
    }

    public long maxContractReward() {
        return maxContractReward;
    }

    public int maxOpenContractsPerPlayer() {
        return maxOpenContractsPerPlayer;
    }

    public int maxAcceptedContractsPerOrganization() {
        return maxAcceptedContractsPerOrganization;
    }

    private static OrgContractsConfig readJson5(Path path) {
        try {
            JsonObject root = Jankson.builder().build().load(path.toFile());
            JsonObject organizations = section(root, "organizations");
            JsonObject contracts = section(root, "contracts");

            boolean organizationsEnabled = bool(organizations, "enabled", true);
            boolean allowPlayerOrganizationCreate = bool(organizations, "allowPlayerCreate", true);
            int minOrganizationNameLength = positiveInt(organizations, "minNameLength", 3);
            int maxOrganizationNameLength = positiveInt(organizations, "maxNameLength", 24);
            if (minOrganizationNameLength > maxOrganizationNameLength) {
                minOrganizationNameLength = 3;
                maxOrganizationNameLength = 24;
            }

            boolean contractsEnabled = bool(contracts, "enabled", true);
            boolean allowPlayerContractCreate = bool(contracts, "allowPlayerCreate", true);
            long minContractReward = positiveLong(contracts, "minReward", 1L);
            long maxContractReward = positiveLong(contracts, "maxReward", 1_000_000L);
            if (minContractReward > maxContractReward) {
                minContractReward = 1L;
                maxContractReward = 1_000_000L;
            }

            int maxOpenContractsPerPlayer = positiveInt(contracts, "maxOpenPerPlayer", 3);
            int maxAcceptedContractsPerOrganization = positiveInt(contracts, "maxAcceptedPerOrganization", 5);

            return new OrgContractsConfig(
                organizationsEnabled,
                allowPlayerOrganizationCreate,
                minOrganizationNameLength,
                maxOrganizationNameLength,
                contractsEnabled,
                allowPlayerContractCreate,
                minContractReward,
                maxContractReward,
                maxOpenContractsPerPlayer,
                maxAcceptedContractsPerOrganization
            );
        } catch (IOException | SyntaxError exception) {
            PowerRubleOrgContractsMod.logger().warn("Could not read {}, using defaults", path, exception);
            return null;
        }
    }

    private static void writeJson5(Path path, OrgContractsConfig config) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, config.toJson5());
        } catch (IOException exception) {
            PowerRubleOrgContractsMod.logger().warn("Could not write {}", path, exception);
        }
    }

    private String toJson5() {
        return """
            {
              organizations: {
                enabled: %s,
                allowPlayerCreate: %s,
                minNameLength: %d,
                maxNameLength: %d
              },
              contracts: {
                enabled: %s,
                allowPlayerCreate: %s,
                minReward: %d,
                maxReward: %d,
                maxOpenPerPlayer: %d,
                maxAcceptedPerOrganization: %d
              }
            }
            """.formatted(
            organizationsEnabled,
            allowPlayerOrganizationCreate,
            minOrganizationNameLength,
            maxOrganizationNameLength,
            contractsEnabled,
            allowPlayerContractCreate,
            minContractReward,
            maxContractReward,
            maxOpenContractsPerPlayer,
            maxAcceptedContractsPerOrganization
        );
    }

    private static OrgContractsConfig defaults() {
        return new OrgContractsConfig(true, true, 3, 24, true, true, 1L, 1_000_000L, 3, 5);
    }

    private static JsonObject section(JsonObject root, String key) {
        Object value = root.get(key);
        return value instanceof JsonObject object ? object : new JsonObject();
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        Object value = root.get(key);
        if (value instanceof blue.endless.jankson.JsonPrimitive primitive && primitive.getValue() instanceof Boolean boolValue) {
            return boolValue;
        }
        return fallback;
    }

    private static int positiveInt(JsonObject root, String key, int fallback) {
        Object value = root.get(key);
        if (value instanceof blue.endless.jankson.JsonPrimitive primitive && primitive.getValue() instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        return fallback;
    }

    private static long positiveLong(JsonObject root, String key, long fallback) {
        Object value = root.get(key);
        if (value instanceof blue.endless.jankson.JsonPrimitive primitive && primitive.getValue() instanceof Number number) {
            return Math.max(1L, number.longValue());
        }
        return fallback;
    }
}
