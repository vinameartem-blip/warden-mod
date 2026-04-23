package ru.warden.mod;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WardenModule implements ModInitializer {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

    // Разделяем состояния на ИВЕНТ (Фарм) и БАЗУ (Хранилище и Зелья)
    private enum State {
        IDLE,
        EVENT_SEARCHING, EVENT_MOVING, EVENT_LOOTING,
        BASE_SEARCHING, BASE_MOVING, BASE_TAKE_POTION, DRINK_POTION,
        CLAN_STORAGE_OPEN, CLAN_STORAGE_FILL,
        DEATH_STEP_1, DEATH_STEP_2, DEATH_STEP_3
    }

    private State currentState = State.IDLE;
    private int tickTimer = 0;
    private int slotsFilled = 0; // Теперь считаем именно занятые слоты
    private final int MAX_SLOTS = 15;
    private final List<BlockPos> ignoredChests = new ArrayList<>();
    private BlockPos targetChest = null;
    private boolean wasDead = false;

    @Override
    public void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            
            // Проверка на смерть/возрождение
            if (client.player.getHealth() <= 0) {
                if (!wasDead) {
                    ignoredChests.clear(); // Сбрасываем сундуки при смерти!
                }
                wasDead = true;
            } else if (wasDead && client.player.isAlive()) {
                wasDead = false;
                slotsFilled = 0;
                currentState = State.DEATH_STEP_1;
                tickTimer = 20; // Пауза после респавна
            }

            if (currentState != State.IDLE) {
                runLogic();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("warden")
                .then(ClientCommandManager.literal("start").executes(context -> {
                    startBot();
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(context -> {
                    stopBot();
                    return 1;
                })));
        });
    }

    private void runLogic() {
        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

        switch (currentState) {
            // --- ЦЕПОЧКА ПОСЛЕ СМЕРТИ ---
            case DEATH_STEP_1:
                sendTP("/home home", State.DEATH_STEP_2, 160);
                break;
            case DEATH_STEP_2:
                sendTP("/clan home", State.DEATH_STEP_3, 160);
                break;
            case DEATH_STEP_3:
                sendTP("/home home", State.EVENT_SEARCHING, 160);
                break;

            // --- ЭТАП ФАРМА (ИВЕНТ НА /home home) ---
            case EVENT_SEARCHING:
                findEventChest();
                break;

            case EVENT_MOVING:
                if (!baritone.getPathingBehavior().isPathing()) {
                    if (targetChest != null && targetChest.isWithinDistance(client.player.getPos(), 4)) {
                        currentState = State.EVENT_LOOTING;
                    } else {
                        currentState = State.EVENT_SEARCHING;
                    }
                }
                break;

            case EVENT_LOOTING:
                openChest();
                if (interactWithGui(false)) { // Забираем лут
                    ignoredChests.add(targetChest);
                    if (slotsFilled >= MAX_SLOTS) {
                        // Забили 15 слотов -> летим на базу
                        sendTP("/clan home", State.BASE_SEARCHING, 160);
                    } else {
                        currentState = State.EVENT_SEARCHING;
                    }
                }
                break;

            // --- ЭТАП БАЗЫ (/clan home) ---
            case BASE_SEARCHING:
                findBaseChest();
                break;

            case BASE_MOVING:
                if (!baritone.getPathingBehavior().isPathing()) {
                    if (targetChest != null && targetChest.isWithinDistance(client.player.getPos(), 4)) {
                        currentState = State.BASE_TAKE_POTION;
                    } else {
                        currentState = State.CLAN_STORAGE_OPEN;
                    }
                }
                break;

            case BASE_TAKE_POTION:
                openChest();
                if (managePotion()) { // Берем РОВНО 1 невидимость
                    currentState = State.DRINK_POTION;
                    tickTimer = 5;
                } else {
                    // Зелий нет, просто скидываем лут
                    currentState = State.CLAN_STORAGE_OPEN;
                }
                break;

            case DRINK_POTION:
                usePotion();
                currentState = State.CLAN_STORAGE_OPEN;
                tickTimer = 40; // Время на анимацию питья
                break;

            case CLAN_STORAGE_OPEN:
                client.player.networkHandler.sendChatCommand("clan storage");
                currentState = State.CLAN_STORAGE_FILL;
                tickTimer = 25; // Задержка для прогрузки GUI хранилища
                break;

            case CLAN_STORAGE_FILL:
                if (interactWithGui(true)) {
                    slotsFilled = 0; // Сброс счетчика слотов
                    sendTP("/home home", State.EVENT_SEARCHING, 160); // Возврат на ивент
                }
                break;
        }
    }

    private void sendTP(String cmd, State nextState, int delay) {
        client.player.networkHandler.sendChatCommand(cmd.replace("/", ""));
        currentState = nextState;
        tickTimer = delay;
        baritone.getPathingBehavior().cancelEverything();
    }

    private void findEventChest() {
        Optional<BlockPos> chest = baritone.getWorldScanner().findNearestBlock(
                client.player.getBlockPos(),
                blockState -> blockState.isOf(Blocks.CHEST) || blockState.isOf(Blocks.TRAPPED_CHEST),
                64, 20, 64
        );

        chest.ifPresentOrElse(pos -> {
            if (!ignoredChests.contains(pos)) {
                targetChest = pos;
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
                currentState = State.EVENT_MOVING;
            }
            // Примечание: если Baritone постоянно находит уже открытый сундук,
            // он не пойдет к нему (условие не выполнится) и продолжит поиск на следующем тике.
        }, () -> stopBot()); // Если сундуков на ивенте не осталось — стоп
    }

    private void findBaseChest() {
        // Ищем сундук на базе (для инвиза). Игнор-лист здесь не учитывается!
        Optional<BlockPos> chest = baritone.getWorldScanner().findNearestBlock(
                client.player.getBlockPos(),
                blockState -> blockState.isOf(Blocks.CHEST) || blockState.isOf(Blocks.TRAPPED_CHEST),
                64, 20, 64
        );

        chest.ifPresentOrElse(pos -> {
            targetChest = pos;
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
            currentState = State.BASE_MOVING;
        }, () -> {
            // Если на базе нет сундуков, сразу открываем хранилище
            currentState = State.CLAN_STORAGE_OPEN;
        });
    }

    private void openChest() {
        client.interactionManager.interactBlock(client.player, client.world, Hand.MAIN_HAND, 
            new net.minecraft.util.hit.BlockHitResult(client.player.getPos(), Direction.UP, targetChest, false));
    }

    private boolean managePotion() {
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler container)) return false;
        
        for (int i = 0; i < container.getInventory().size(); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (stack.getItem() == Items.POTION || stack.getItem() == Items.SPLASH_POTION) {
                if (stack.getNbt() != null && stack.getNbt().toString().contains("invisibility")) {
                    client.interactionManager.clickSlot(container.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                    client.player.closeHandledScreen();
                    return true; // Взяли ровно 1 зелье и прервали цикл
                }
            }
        }
        client.player.closeHandledScreen();
        return false;
    }

    private void usePotion() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getNbt() != null && stack.getNbt().toString().contains("invisibility")) {
                client.player.getInventory().selectedSlot = i;
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                return;
            }
        }
    }

    private boolean interactWithGui(boolean toContainer) {
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler container)) return false;

        int chestSlots = container.getInventory().size();
        int totalSlots = container.slots.size();

        for (int i = (toContainer ? chestSlots : 0); i < (toContainer ? totalSlots : chestSlots); i++) {
            ItemStack stack = container.getSlot(i).getStack();
            if (!stack.isEmpty()) {
                // Если мы лутаем сундук и слотов уже >= 15, прерываемся
                if (!toContainer && slotsFilled >= MAX_SLOTS) {
                    break;
                }
                
                client.interactionManager.clickSlot(container.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                
                if (!toContainer) {
                    slotsFilled++; // Считаем именно занятые СЛОТЫ, а не количество предметов
                }
                
                try { Thread.sleep(10); } catch (Exception ignored) {} 
            }
        }
        client.player.closeHandledScreen();
        return true;
    }

    private void startBot() {
        slotsFilled = 0;
        ignoredChests.clear();
        // При старте предполагается, что мы на ивенте
        currentState = State.EVENT_SEARCHING;
        client.player.sendMessage(Text.literal("§a[Warden] Система активирована"), false);
    }

    private void stopBot() {
        currentState = State.IDLE;
        baritone.getPathingBehavior().cancelEverything();
        client.player.sendMessage(Text.literal("§c[Warden] Система остановлена"), false);
    }
                  }
