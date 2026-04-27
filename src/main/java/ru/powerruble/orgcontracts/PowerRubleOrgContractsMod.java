package ru.powerruble.orgcontracts;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.powerruble.RubleState;
import ru.powerruble.RubleTransaction;
import ru.powerruble.api.PowerRuble;

public final class PowerRubleOrgContractsMod implements ModInitializer {
    public static final String MOD_ID = "power-ruble-orgcontracts";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static OrgContractsConfig config;
    private static final Duration INVITE_TTL = Duration.ofMinutes(5);
    private static final SimpleCommandExceptionType ALREADY_IN_ORGANIZATION =
        new SimpleCommandExceptionType(Text.literal("You are already in an organization."));
    private static final SimpleCommandExceptionType ORGANIZATION_EXISTS =
        new SimpleCommandExceptionType(Text.literal("An organization with that name already exists."));
    private static final SimpleCommandExceptionType ORGANIZATION_REQUIRED =
        new SimpleCommandExceptionType(Text.literal("You are not in an organization."));
    private static final SimpleCommandExceptionType INVITE_REQUIRED =
        new SimpleCommandExceptionType(Text.literal("You do not have a pending organization invite."));
    private static final SimpleCommandExceptionType INVITE_EXPIRED =
        new SimpleCommandExceptionType(Text.literal("Your organization invite expired."));
    private static final SimpleCommandExceptionType NO_ORG_PERMISSION =
        new SimpleCommandExceptionType(Text.literal("You do not have permission for that organization action."));
    private static final SimpleCommandExceptionType TARGET_ALREADY_IN_ORG =
        new SimpleCommandExceptionType(Text.literal("That player is already in an organization."));
    private static final SimpleCommandExceptionType SELF_TARGET =
        new SimpleCommandExceptionType(Text.literal("You cannot target yourself."));
    private static final SimpleCommandExceptionType TARGET_NOT_IN_YOUR_ORG =
        new SimpleCommandExceptionType(Text.literal("That player is not in your organization."));
    private static final SimpleCommandExceptionType NOT_ENOUGH_MONEY =
        new SimpleCommandExceptionType(Text.literal("Not enough money."));
    private static final SimpleCommandExceptionType INVALID_ITEM =
        new SimpleCommandExceptionType(Text.literal("Invalid item id."));
    private static final SimpleCommandExceptionType CONTRACT_NOT_FOUND =
        new SimpleCommandExceptionType(Text.literal("Contract not found."));
    private static final SimpleCommandExceptionType CONTRACT_NOT_OPEN =
        new SimpleCommandExceptionType(Text.literal("Contract is not open."));
    private static final SimpleCommandExceptionType CONTRACT_NOT_ACCEPTED =
        new SimpleCommandExceptionType(Text.literal("Contract is not accepted."));
    private static final SimpleCommandExceptionType CONTRACT_NOT_YOUR_ORG =
        new SimpleCommandExceptionType(Text.literal("Your organization did not accept this contract."));
    private static final SimpleCommandExceptionType NOT_ENOUGH_ITEMS =
        new SimpleCommandExceptionType(Text.literal("Not enough matching items."));
    private static final SimpleCommandExceptionType CONTRACT_CANCEL_NOT_ALLOWED =
        new SimpleCommandExceptionType(Text.literal("You cannot cancel this contract."));
    private static final SimpleCommandExceptionType ORGANIZATIONS_DISABLED =
        new SimpleCommandExceptionType(Text.literal("Organizations are disabled in config."));
    private static final SimpleCommandExceptionType CONTRACTS_DISABLED =
        new SimpleCommandExceptionType(Text.literal("Contracts are disabled in config."));
    private static final SimpleCommandExceptionType ORG_CREATE_DISABLED =
        new SimpleCommandExceptionType(Text.literal("Players cannot create organizations by config."));
    private static final SimpleCommandExceptionType CONTRACT_CREATE_DISABLED =
        new SimpleCommandExceptionType(Text.literal("Players cannot create contracts by config."));
    private static final SimpleCommandExceptionType CONTRACT_REWARD_OUT_OF_RANGE =
        new SimpleCommandExceptionType(Text.literal("Contract reward is outside configured limits."));
    private static final SimpleCommandExceptionType PLAYER_OPEN_CONTRACT_LIMIT_REACHED =
        new SimpleCommandExceptionType(Text.literal("You reached the configured open contract limit."));
    private static final SimpleCommandExceptionType ORG_ACCEPTED_CONTRACT_LIMIT_REACHED =
        new SimpleCommandExceptionType(Text.literal("Organization reached the configured accepted contract limit."));
    private static final SimpleCommandExceptionType OWNER_CANNOT_LEAVE =
        new SimpleCommandExceptionType(Text.literal("Owner cannot leave while other members remain."));
    private static final SimpleCommandExceptionType OWNER_CANNOT_BE_KICKED =
        new SimpleCommandExceptionType(Text.literal("Organization owner cannot be removed."));
    private static final SimpleCommandExceptionType INVALID_ORG_NAME =
        new SimpleCommandExceptionType(Text.literal("Organization name must be 3-24 chars and contain only letters, digits, _ or -."));
    private static final Map<java.util.UUID, PendingInvite> pendingInvites = new HashMap<>();

    @Override
    public void onInitialize() {
        config = OrgContractsConfig.load();
        LOGGER.info(
            "Power Ruble org/contracts addon loaded. Base currency: {}",
            PowerRuble.api().config().currencyName()
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                CommandManager.literal("org")
                    .then(CommandManager.literal("create")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(context -> createOrganization(
                                context.getSource(),
                                StringArgumentType.getString(context, "name")
                            ))
                        )
                    )
                    .then(CommandManager.literal("info")
                        .executes(context -> showOrganizationInfo(context.getSource()))
                    )
                    .then(CommandManager.literal("members")
                        .executes(context -> showOrganizationMembers(context.getSource()))
                    )
                    .then(CommandManager.literal("balance")
                        .executes(context -> showOrganizationBalance(context.getSource()))
                    )
                    .then(CommandManager.literal("deposit")
                        .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                            .executes(context -> depositToOrganization(
                                context.getSource(),
                                com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount"),
                                ""
                            ))
                            .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                .executes(context -> depositToOrganization(
                                    context.getSource(),
                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount"),
                                    StringArgumentType.getString(context, "comment")
                                ))
                            )
                        )
                    )
                    .then(CommandManager.literal("pay")
                        .then(CommandManager.argument("player", GameProfileArgumentType.gameProfile())
                            .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                .executes(context -> payFromOrganization(
                                    context.getSource(),
                                    singleProfile(GameProfileArgumentType.getProfileArgument(context, "player")),
                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount"),
                                    ""
                                ))
                                .then(CommandManager.argument("comment", StringArgumentType.greedyString())
                                    .executes(context -> payFromOrganization(
                                        context.getSource(),
                                        singleProfile(GameProfileArgumentType.getProfileArgument(context, "player")),
                                        com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount"),
                                        StringArgumentType.getString(context, "comment")
                                    ))
                                )
                            )
                        )
                    )
                    .then(CommandManager.literal("history")
                        .executes(context -> showOrganizationHistory(context.getSource()))
                    )
                    .then(CommandManager.literal("invite")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(context -> invitePlayer(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "player")
                            ))
                        )
                    )
                    .then(CommandManager.literal("join")
                        .executes(context -> joinOrganization(context.getSource()))
                    )
                    .then(CommandManager.literal("leave")
                        .executes(context -> leaveOrganization(context.getSource()))
                    )
                    .then(CommandManager.literal("kick")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(context -> kickPlayer(
                                context.getSource(),
                                EntityArgumentType.getPlayer(context, "player")
                            ))
                        )
                    )
                    .then(CommandManager.literal("role")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                            .then(CommandManager.literal("manager")
                                .executes(context -> setRole(
                                    context.getSource(),
                                    EntityArgumentType.getPlayer(context, "player"),
                                    OrgRole.MANAGER
                                ))
                            )
                            .then(CommandManager.literal("member")
                                .executes(context -> setRole(
                                    context.getSource(),
                                    EntityArgumentType.getPlayer(context, "player"),
                                    OrgRole.MEMBER
                                ))
                            )
                        )
                    )
            );

            dispatcher.register(
                CommandManager.literal("contract")
                    .then(CommandManager.literal("create")
                        .then(CommandManager.literal("item")
                            .then(CommandManager.argument("item", StringArgumentType.word())
                                .then(CommandManager.argument("count", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                    .then(CommandManager.argument("reward", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                        .executes(context -> createItemContract(
                                            context.getSource(),
                                            StringArgumentType.getString(context, "item"),
                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "count"),
                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "reward")
                                        ))
                                    )
                                )
                            )
                        )
                    )
                    .then(CommandManager.literal("list")
                        .executes(context -> listContracts(context.getSource()))
                    )
                    .then(CommandManager.literal("view")
                        .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(context -> viewContract(
                                context.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id")
                            ))
                        )
                    )
                    .then(CommandManager.literal("accept")
                        .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(context -> acceptContract(
                                context.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id")
                            ))
                        )
                    )
                    .then(CommandManager.literal("deliver")
                        .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .then(CommandManager.argument("amount", com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                .executes(context -> deliverContract(
                                    context.getSource(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id"),
                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "amount")
                                ))
                            )
                        )
                    )
                    .then(CommandManager.literal("cancel")
                        .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(context -> cancelContract(
                                context.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id")
                            ))
                        )
                    )
                    .then(CommandManager.literal("history")
                        .then(CommandManager.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes(context -> showContractHistory(
                                context.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "id")
                            ))
                        )
                    )
            );

            dispatcher.register(
                CommandManager.literal("orgcontracts")
                    .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> reloadConfig(context.getSource()))
                    )
            );
        });
    }

    private static int createOrganization(ServerCommandSource source, String rawName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        if (!config.allowPlayerOrganizationCreate() && !source.hasPermissionLevel(2)) {
            throw ORG_CREATE_DISABLED.create();
        }

        ServerPlayerEntity player = requirePlayer(source);
        String name = rawName.trim();
        validateOrganizationName(name);

        OrganizationState state = OrganizationState.get(source.getServer());
        if (state.hasOrganization(player.getUuid())) {
            throw ALREADY_IN_ORGANIZATION.create();
        }

        if (state.organizationByName(name).isPresent()) {
            throw ORGANIZATION_EXISTS.create();
        }

        OrganizationState.Organization organization = state.createOrganization(player.getUuid(), player.getName().getString(), name)
            .orElseThrow(ORGANIZATION_EXISTS::create);

        PowerRuble.api().rememberAccountName(source.getServer(), organization.id(), organization.name());
        source.sendFeedback(() -> Text.literal("Organization created: " + organization.name()), false);
        return 1;
    }

    private static int showOrganizationInfo(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);
        long balance = PowerRuble.api().getBalance(source.getServer(), organization.id());
        String ownerName = organization.members().get(organization.ownerId()).name();

        source.sendFeedback(() -> Text.literal("Organization: " + organization.name()), false);
        source.sendFeedback(() -> Text.literal("Owner: " + ownerName), false);
        source.sendFeedback(() -> Text.literal("Members: " + organization.members().size()), false);
        source.sendFeedback(() -> Text.literal("Balance: " + balance + " " + PowerRuble.api().config().currencyName()), false);
        return 1;
    }

    private static int showOrganizationMembers(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);

        source.sendFeedback(() -> Text.literal("Members of " + organization.name() + ":"), false);
        for (OrganizationState.Member member : organization.sortedMembers()) {
            source.sendFeedback(() -> Text.literal("- " + member.name() + " [" + member.role().name().toLowerCase() + "]"), false);
        }
        return 1;
    }

    private static int showOrganizationBalance(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);
        long balance = PowerRuble.api().getBalance(source.getServer(), organization.id());

        source.sendFeedback(
            () -> Text.literal(organization.name() + " balance: " + balance + " " + PowerRuble.api().config().currencyName()),
            false
        );
        return 1;
    }

    private static int depositToOrganization(ServerCommandSource source, long amount, String comment)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);
        RubleState.TransferResult result = PowerRuble.api().transfer(
            source.getServer(),
            player.getUuid(),
            organization.id(),
            null,
            amount,
            0L,
            0L
        );

        if (result == RubleState.TransferResult.NOT_ENOUGH_MONEY) {
            throw NOT_ENOUGH_MONEY.create();
        }

        if (result != RubleState.TransferResult.OK) {
            throw new SimpleCommandExceptionType(Text.literal("Could not complete organization deposit.")).create();
        }

        String reason = normalizeComment(comment, "org deposit");
        PowerRuble.api().addTransaction(
            source.getServer(),
            RubleTransaction.transfer(
                Instant.now(),
                player.getUuid(),
                player.getName().getString(),
                organization.id(),
                organization.name(),
                amount,
                0L,
                Optional.empty(),
                "",
                reason
            )
        );

        source.sendFeedback(
            () -> Text.literal("Deposited " + format(amount) + " to " + organization.name() + "."),
            false
        );
        return 1;
    }

    private static int payFromOrganization(ServerCommandSource source, com.mojang.authlib.GameProfile profile, long amount, String comment)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity actor = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, actor);
        OrganizationState.Member actorMember = requireMembership(organization, actor.getUuid());
        requireManager(actorMember);

        UUID targetId = profileId(profile);
        if (targetId.equals(organization.id())) {
            throw SELF_TARGET.create();
        }

        PowerRuble.api().rememberAccountName(source.getServer(), targetId, profile.getName());
        RubleState.TransferResult result = PowerRuble.api().transfer(
            source.getServer(),
            organization.id(),
            targetId,
            null,
            amount,
            0L,
            0L
        );

        if (result == RubleState.TransferResult.NOT_ENOUGH_MONEY) {
            throw NOT_ENOUGH_MONEY.create();
        }

        if (result != RubleState.TransferResult.OK) {
            throw new SimpleCommandExceptionType(Text.literal("Could not complete organization payment.")).create();
        }

        PowerRuble.api().addTransaction(
            source.getServer(),
            new RubleTransaction(
                UUID.randomUUID().toString(),
                RubleTransaction.Type.TRANSFER,
                Instant.now(),
                Optional.of(actor.getUuid()),
                actor.getName().getString(),
                Optional.of(organization.id()),
                organization.name(),
                Optional.of(targetId),
                profile.getName(),
                amount,
                0L,
                Optional.empty(),
                "",
                normalizeComment(comment, "org payout"),
                ""
            )
        );

        source.sendFeedback(
            () -> Text.literal("Paid " + format(amount) + " from " + organization.name() + " to " + profile.getName() + "."),
            false
        );
        return 1;
    }

    private static int showOrganizationHistory(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);
        var transactions = PowerRuble.api().state(source.getServer()).getTransactions(organization.id(), 10);
        if (transactions.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No organization history yet."), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("History for " + organization.name() + ":"), false);
        for (RubleTransaction transaction : transactions) {
            source.sendFeedback(() -> Text.literal("- " + transaction.describe(PowerRuble.api().config().currencyName())), false);
        }
        return 1;
    }

    private static int createItemContract(ServerCommandSource source, String rawItemId, long requiredAmount, long reward)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireContractsEnabled();
        if (!config.allowPlayerContractCreate() && !source.hasPermissionLevel(2)) {
            throw CONTRACT_CREATE_DISABLED.create();
        }
        if (reward < config.minContractReward() || reward > config.maxContractReward()) {
            throw CONTRACT_REWARD_OUT_OF_RANGE.create();
        }

        ServerPlayerEntity player = requirePlayer(source);
        Item item = resolveItem(rawItemId);
        ContractState state = ContractState.get(source.getServer());
        if (state.openContractsForCreator(player.getUuid()) >= config.maxOpenContractsPerPlayer()) {
            throw PLAYER_OPEN_CONTRACT_LIMIT_REACHED.create();
        }
        ContractState.Contract contract = state.createItemDelivery(
            player.getUuid(),
            player.getName().getString(),
            Registries.ITEM.getId(item).toString(),
            requiredAmount,
            reward
        );

        PowerRuble.api().rememberAccountName(source.getServer(), contract.escrowAccountId(), "contract#" + contract.id() + " escrow");
        RubleState.TransferResult transferResult = PowerRuble.api().transfer(
            source.getServer(),
            player.getUuid(),
            contract.escrowAccountId(),
            null,
            reward,
            0L,
            0L
        );
        if (transferResult == RubleState.TransferResult.NOT_ENOUGH_MONEY) {
            throw NOT_ENOUGH_MONEY.create();
        }
        if (transferResult != RubleState.TransferResult.OK) {
            throw new SimpleCommandExceptionType(Text.literal("Could not create contract escrow.")).create();
        }

        PowerRuble.api().addTransaction(
            source.getServer(),
            RubleTransaction.transfer(
                Instant.now(),
                player.getUuid(),
                player.getName().getString(),
                contract.escrowAccountId(),
                "contract#" + contract.id() + " escrow",
                reward,
                0L,
                Optional.empty(),
                "",
                "contract escrow #" + contract.id()
            )
        );

        source.sendFeedback(
            () -> Text.literal("Created contract #" + contract.id() + ": deliver " + requiredAmount + " " + contract.itemId() + " for " + format(reward) + "."),
            false
        );
        return 1;
    }

    private static int listContracts(ServerCommandSource source) {
        if (!config.contractsEnabled()) {
            source.sendFeedback(() -> Text.literal("Contracts are disabled in config."), false);
            return 1;
        }
        var contracts = ContractState.get(source.getServer()).allContracts();
        if (contracts.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No contracts."), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("Contracts:"), false);
        for (ContractState.Contract contract : contracts) {
            source.sendFeedback(
                () -> Text.literal(
                    "#" + contract.id()
                        + " [" + contract.status().name().toLowerCase() + "] "
                        + contract.itemId() + " x" + contract.requiredAmount()
                        + ", delivered " + contract.deliveredAmount()
                        + ", reward " + format(contract.reward())
                ),
                false
            );
        }
        return 1;
    }

    private static int viewContract(ServerCommandSource source, int id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireContractsEnabled();
        ContractState.Contract contract = ContractState.get(source.getServer()).getContract(id).orElseThrow(CONTRACT_NOT_FOUND::create);
        source.sendFeedback(() -> Text.literal("Contract #" + contract.id()), false);
        source.sendFeedback(() -> Text.literal("Status: " + contract.status().name().toLowerCase()), false);
        source.sendFeedback(() -> Text.literal("Creator: " + contract.creatorName()), false);
        source.sendFeedback(() -> Text.literal("Item: " + contract.itemId()), false);
        source.sendFeedback(() -> Text.literal("Required: " + contract.requiredAmount()), false);
        source.sendFeedback(() -> Text.literal("Delivered: " + contract.deliveredAmount()), false);
        source.sendFeedback(() -> Text.literal("Reward: " + format(contract.reward())), false);
        if (contract.acceptedOrganizationId().isPresent()) {
            source.sendFeedback(() -> Text.literal("Organization: " + contract.acceptedOrganizationName()), false);
        }
        return 1;
    }

    private static int acceptContract(ServerCommandSource source, int id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireContractsEnabled();
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);
        OrganizationState.Member member = requireMembership(organization, player.getUuid());
        requireManager(member);

        ContractState state = ContractState.get(source.getServer());
        if (state.acceptedContractsForOrganization(organization.id()) >= config.maxAcceptedContractsPerOrganization()) {
            throw ORG_ACCEPTED_CONTRACT_LIMIT_REACHED.create();
        }
        ContractState.Contract contract = state.getContract(id).orElseThrow(CONTRACT_NOT_FOUND::create);
        if (contract.status() != ContractState.ContractStatus.OPEN) {
            throw CONTRACT_NOT_OPEN.create();
        }

        if (!state.accept(id, organization.id(), organization.name(), player.getName().getString())) {
            throw CONTRACT_NOT_OPEN.create();
        }

        source.sendFeedback(() -> Text.literal(organization.name() + " accepted contract #" + id + "."), false);
        return 1;
    }

    private static int deliverContract(ServerCommandSource source, int id, long amount)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireContractsEnabled();
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, player);
        ContractState state = ContractState.get(source.getServer());
        ContractState.Contract contract = state.getContract(id).orElseThrow(CONTRACT_NOT_FOUND::create);
        if (contract.status() == ContractState.ContractStatus.OPEN) {
            throw CONTRACT_NOT_ACCEPTED.create();
        }
        if (contract.acceptedOrganizationId().isEmpty() || !contract.acceptedOrganizationId().get().equals(organization.id())) {
            throw CONTRACT_NOT_YOUR_ORG.create();
        }

        Item item = resolveItem(contract.itemId());
        long remaining = contract.remainingAmount();
        long deliverAmount = Math.min(amount, remaining);
        if (countItems(player, item) < deliverAmount) {
            throw NOT_ENOUGH_ITEMS.create();
        }

        removeItems(player, item, deliverAmount);
        ContractState.Contract updated = state.deliver(id, deliverAmount, player.getName().getString()).orElseThrow(CONTRACT_NOT_ACCEPTED::create);

        source.sendFeedback(
            () -> Text.literal("Delivered " + deliverAmount + " " + contract.itemId() + " to contract #" + id + "."),
            false
        );

        if (updated.status() == ContractState.ContractStatus.COMPLETED) {
            RubleState.TransferResult result = PowerRuble.api().transfer(
                source.getServer(),
                updated.escrowAccountId(),
                organization.id(),
                null,
                updated.reward(),
                0L,
                0L
            );
            if (result != RubleState.TransferResult.OK) {
                throw new SimpleCommandExceptionType(Text.literal("Contract reward transfer failed.")).create();
            }

            PowerRuble.api().addTransaction(
                source.getServer(),
                RubleTransaction.transfer(
                    Instant.now(),
                    updated.escrowAccountId(),
                    "contract#" + updated.id() + " escrow",
                    organization.id(),
                    organization.name(),
                    updated.reward(),
                    0L,
                    Optional.empty(),
                    "",
                    "contract reward #" + updated.id()
                )
            );

            source.sendFeedback(
                () -> Text.literal("Contract #" + id + " completed. " + format(updated.reward()) + " paid to " + organization.name() + "."),
                false
            );
        }
        return 1;
    }

    private static int cancelContract(ServerCommandSource source, int id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireContractsEnabled();
        ContractState state = ContractState.get(source.getServer());
        ContractState.Contract contract = state.getContract(id).orElseThrow(CONTRACT_NOT_FOUND::create);

        boolean isAdmin = source.hasPermissionLevel(2);
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;
        boolean isCreator = player != null && player.getUuid().equals(contract.creatorId());

        if (contract.status() == ContractState.ContractStatus.OPEN) {
            if (!isAdmin && !isCreator) {
                throw CONTRACT_CANCEL_NOT_ALLOWED.create();
            }
        } else if (contract.status() == ContractState.ContractStatus.ACCEPTED) {
            if (!isAdmin) {
                throw CONTRACT_CANCEL_NOT_ALLOWED.create();
            }
        } else {
            throw CONTRACT_CANCEL_NOT_ALLOWED.create();
        }

        ContractState.Contract cancelled = state.cancel(id, actorName(source)).orElseThrow(CONTRACT_CANCEL_NOT_ALLOWED::create);
        long escrowBalance = PowerRuble.api().getBalance(source.getServer(), cancelled.escrowAccountId());
        if (escrowBalance > 0L) {
            RubleState.TransferResult result = PowerRuble.api().transfer(
                source.getServer(),
                cancelled.escrowAccountId(),
                cancelled.creatorId(),
                null,
                escrowBalance,
                0L,
                0L
            );
            if (result != RubleState.TransferResult.OK) {
                throw new SimpleCommandExceptionType(Text.literal("Could not refund contract escrow.")).create();
            }

            PowerRuble.api().addTransaction(
                source.getServer(),
                RubleTransaction.transfer(
                    Instant.now(),
                    cancelled.escrowAccountId(),
                    "contract#" + cancelled.id() + " escrow",
                    cancelled.creatorId(),
                    cancelled.creatorName(),
                    escrowBalance,
                    0L,
                    Optional.empty(),
                    "",
                    "contract refund #" + cancelled.id()
                )
            );
        }

        source.sendFeedback(() -> Text.literal("Contract #" + id + " cancelled."), false);
        return 1;
    }

    private static int showContractHistory(ServerCommandSource source, int id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireContractsEnabled();
        ContractState.Contract contract = ContractState.get(source.getServer()).getContract(id).orElseThrow(CONTRACT_NOT_FOUND::create);
        source.sendFeedback(() -> Text.literal("History for contract #" + contract.id() + ":"), false);
        for (String entry : contract.history()) {
            source.sendFeedback(() -> Text.literal("- " + entry), false);
        }
        return 1;
    }

    private static int invitePlayer(ServerCommandSource source, ServerPlayerEntity target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity actor = requirePlayer(source);
        if (actor.getUuid().equals(target.getUuid())) {
            throw SELF_TARGET.create();
        }

        OrganizationState.Organization organization = requireOrganization(source, actor);
        OrganizationState.Member actorMember = requireMembership(organization, actor.getUuid());
        requireManager(actorMember);

        OrganizationState state = OrganizationState.get(source.getServer());
        if (state.hasOrganization(target.getUuid())) {
            throw TARGET_ALREADY_IN_ORG.create();
        }

        pendingInvites.put(
            target.getUuid(),
            new PendingInvite(organization.id(), organization.name(), actor.getUuid(), actor.getName().getString(), Instant.now().plus(INVITE_TTL))
        );

        source.sendFeedback(() -> Text.literal("Invite sent to " + target.getName().getString() + "."), false);
        target.sendMessage(Text.literal(actor.getName().getString() + " invited you to " + organization.name() + ". Use /org join."), false);
        return 1;
    }

    private static int joinOrganization(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState state = OrganizationState.get(source.getServer());
        if (state.hasOrganization(player.getUuid())) {
            throw ALREADY_IN_ORGANIZATION.create();
        }

        PendingInvite invite = pendingInvites.get(player.getUuid());
        if (invite == null) {
            throw INVITE_REQUIRED.create();
        }

        if (invite.expiresAt().isBefore(Instant.now())) {
            pendingInvites.remove(player.getUuid());
            throw INVITE_EXPIRED.create();
        }

        Optional<OrganizationState.Organization> organization = state.organizationFor(invite.inviterId())
            .filter(existing -> existing.id().equals(invite.organizationId()));
        if (organization.isEmpty()) {
            pendingInvites.remove(player.getUuid());
            throw INVITE_REQUIRED.create();
        }

        boolean joined = state.addMember(organization.get().id(), player.getUuid(), player.getName().getString(), OrgRole.MEMBER);
        pendingInvites.remove(player.getUuid());
        if (!joined) {
            throw INVITE_REQUIRED.create();
        }

        source.sendFeedback(() -> Text.literal("You joined " + organization.get().name() + "."), false);
        ServerPlayerEntity inviter = source.getServer().getPlayerManager().getPlayer(invite.inviterId());
        if (inviter != null) {
            inviter.sendMessage(Text.literal(player.getName().getString() + " joined " + organization.get().name() + "."), false);
        }
        return 1;
    }

    private static int leaveOrganization(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity player = requirePlayer(source);
        OrganizationState state = OrganizationState.get(source.getServer());
        OrganizationState.Organization organization = requireOrganization(source, player);
        OrganizationState.Member member = requireMembership(organization, player.getUuid());

        if (member.role() == OrgRole.OWNER) {
            if (organization.members().size() > 1) {
                throw OWNER_CANNOT_LEAVE.create();
            }

            state.deleteOrganization(organization.id());
            PowerRuble.api().setBalance(source.getServer(), organization.id(), 0L);
            source.sendFeedback(() -> Text.literal("Organization " + organization.name() + " was disbanded."), false);
            return 1;
        }

        state.removeMember(organization.id(), player.getUuid());
        source.sendFeedback(() -> Text.literal("You left " + organization.name() + "."), false);
        return 1;
    }

    private static int kickPlayer(ServerCommandSource source, ServerPlayerEntity target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity actor = requirePlayer(source);
        if (actor.getUuid().equals(target.getUuid())) {
            throw SELF_TARGET.create();
        }

        OrganizationState state = OrganizationState.get(source.getServer());
        OrganizationState.Organization organization = requireOrganization(source, actor);
        OrganizationState.Member actorMember = requireMembership(organization, actor.getUuid());
        OrganizationState.Member targetMember = organization.members().get(target.getUuid());
        if (targetMember == null) {
            throw TARGET_NOT_IN_YOUR_ORG.create();
        }

        if (targetMember.role() == OrgRole.OWNER) {
            throw OWNER_CANNOT_BE_KICKED.create();
        }

        if (actorMember.role() == OrgRole.MEMBER) {
            throw NO_ORG_PERMISSION.create();
        }

        if (actorMember.role() == OrgRole.MANAGER && targetMember.role() != OrgRole.MEMBER) {
            throw NO_ORG_PERMISSION.create();
        }

        state.removeMember(organization.id(), target.getUuid());
        source.sendFeedback(() -> Text.literal("Removed " + target.getName().getString() + " from " + organization.name() + "."), false);
        target.sendMessage(Text.literal("You were removed from " + organization.name() + "."), false);
        return 1;
    }

    private static int setRole(ServerCommandSource source, ServerPlayerEntity target, OrgRole role)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        requireOrganizationsEnabled();
        ServerPlayerEntity actor = requirePlayer(source);
        OrganizationState.Organization organization = requireOrganization(source, actor);
        OrganizationState.Member actorMember = requireMembership(organization, actor.getUuid());
        if (actorMember.role() != OrgRole.OWNER) {
            throw NO_ORG_PERMISSION.create();
        }

        if (actor.getUuid().equals(target.getUuid())) {
            throw SELF_TARGET.create();
        }

        OrganizationState.Member targetMember = organization.members().get(target.getUuid());
        if (targetMember == null) {
            throw TARGET_NOT_IN_YOUR_ORG.create();
        }

        if (targetMember.role() == OrgRole.OWNER) {
            throw OWNER_CANNOT_BE_KICKED.create();
        }

        OrganizationState.get(source.getServer()).setRole(organization.id(), target.getUuid(), role);
        source.sendFeedback(() -> Text.literal(target.getName().getString() + " role set to " + role.name().toLowerCase() + "."), false);
        target.sendMessage(Text.literal("Your role in " + organization.name() + " is now " + role.name().toLowerCase() + "."), false);
        return 1;
    }

    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return source.getPlayerOrThrow();
    }

    private static OrganizationState.Organization requireOrganization(ServerCommandSource source, ServerPlayerEntity player)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Optional<OrganizationState.Organization> organization = OrganizationState.get(source.getServer()).organizationFor(player.getUuid());
        if (organization.isEmpty()) {
            throw ORGANIZATION_REQUIRED.create();
        }

        return organization.get();
    }

    private static int reloadConfig(ServerCommandSource source) {
        config = OrgContractsConfig.load();
        source.sendFeedback(() -> Text.literal("Power Ruble org/contracts config reloaded."), true);
        return 1;
    }

    private static OrganizationState.Member requireMembership(OrganizationState.Organization organization, java.util.UUID playerId)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        OrganizationState.Member member = organization.members().get(playerId);
        if (member == null) {
            throw ORGANIZATION_REQUIRED.create();
        }

        return member;
    }

    private static void requireManager(OrganizationState.Member member) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (member.role() == OrgRole.MEMBER) {
            throw NO_ORG_PERMISSION.create();
        }
    }

    private static com.mojang.authlib.GameProfile singleProfile(java.util.Collection<com.mojang.authlib.GameProfile> profiles)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (profiles.isEmpty()) {
            throw new SimpleCommandExceptionType(Text.literal("Player not found.")).create();
        }

        if (profiles.size() != 1) {
            throw new SimpleCommandExceptionType(Text.literal("Specify exactly one player.")).create();
        }

        return profiles.iterator().next();
    }

    private static UUID profileId(com.mojang.authlib.GameProfile profile) {
        if (profile.getId() != null) {
            return profile.getId();
        }

        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + profile.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Item resolveItem(String rawItemId) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Identifier identifier = Identifier.tryParse(rawItemId);
        if (identifier == null) {
            throw INVALID_ITEM.create();
        }

        return Registries.ITEM.getOrEmpty(identifier).orElseThrow(INVALID_ITEM::create);
    }

    private static long countItems(ServerPlayerEntity player, Item item) {
        long count = 0L;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeItems(ServerPlayerEntity player, Item item, long amount) {
        long remaining = amount;
        for (int slot = 0; slot < player.getInventory().size() && remaining > 0L; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isOf(item)) {
                continue;
            }

            int removed = (int) Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
        }
        player.getInventory().markDirty();
        player.playerScreenHandler.sendContentUpdates();
    }

    private static String actorName(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getName().getString();
        }

        return source.getName();
    }

    private static void validateOrganizationName(String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (name.length() < config.minOrganizationNameLength() || name.length() > config.maxOrganizationNameLength()) {
            throw INVALID_ORG_NAME.create();
        }

        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_' || character == '-') {
                continue;
            }

            throw INVALID_ORG_NAME.create();
        }
    }

    private static String normalizeComment(String comment, String fallback) {
        String normalized = comment == null ? "" : comment.trim();
        return normalized.isBlank() ? fallback : fallback + ", " + normalized;
    }

    private static String format(long amount) {
        return amount + " " + PowerRuble.api().config().currencyName();
    }

    static Logger logger() {
        return LOGGER;
    }

    private static void requireOrganizationsEnabled() throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!config.organizationsEnabled()) {
            throw ORGANIZATIONS_DISABLED.create();
        }
    }

    private static void requireContractsEnabled() throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!config.contractsEnabled()) {
            throw CONTRACTS_DISABLED.create();
        }
    }

    private record PendingInvite(
        java.util.UUID organizationId,
        String organizationName,
        java.util.UUID inviterId,
        String inviterName,
        Instant expiresAt
    ) {
    }
}
