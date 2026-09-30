package gg.auroramc.aurora.expansions.region;

import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.events.region.RegionBlockBreakEvent;
import gg.auroramc.aurora.api.events.region.RegionBlockPlaceEvent;
import org.bukkit.Bukkit;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class RegionBlockListener implements Listener {
    private final Aurora plugin;
    private final RegionExpansion regionExpansion;
    private static final NamespacedKey FALLING_PLACED = new NamespacedKey("aurora", "player_placed_falling_block");
    private final BlockFace[] blockFaces = new BlockFace[]{BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.UP, BlockFace.DOWN};


    public RegionBlockListener(Aurora plugin, RegionExpansion regionExpansion) {
        this.plugin = plugin;
        this.regionExpansion = regionExpansion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void checkPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        regionExpansion.addPlacedBlock(block);
        Bukkit.getPluginManager().callEvent(new RegionBlockPlaceEvent(event.getPlayer(), block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSandFall(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) return;
        Material type = fallingBlock.getBlockData().getMaterial();
        if (type != Material.SAND && type != Material.RED_SAND && type != Material.GRAVEL) return;

        var entityData = fallingBlock.getPersistentDataContainer();
        Block block = event.getBlock();
        if (isAir(event.getTo()) || event.getTo() == Material.WATER) {
            if (block.getType() == type && regionExpansion.isPlacedBlock(block)) {
                entityData.set(FALLING_PLACED, PersistentDataType.BYTE, (byte) 1);
                regionExpansion.removePlacedBlock(block);
            }
        } else if (event.getTo() == type) {
            boolean placed = entityData.has(FALLING_PLACED, PersistentDataType.BYTE);
            entityData.remove(FALLING_PLACED);
            var location = block.getLocation();
            // The event precedes setBlock. Check the accepted landing on its owning region next tick.
            Bukkit.getRegionScheduler().run(plugin, location, task -> {
                if (location.getBlock().getType() != type) return;
                if (placed) regionExpansion.addPlacedBlock(location);
                else regionExpansion.removePlacedBlock(location);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void checkBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        removeBlockWithEvent(player, block);
        checkTallPlant(event.getPlayer(), block, 0, mat -> mat == Material.SUGAR_CANE);
        checkTallPlant(event.getPlayer(), block, 0, mat -> mat == Material.BAMBOO);
        checkTallPlant(event.getPlayer(), block, 0, mat -> mat == Material.CACTUS);
        checkTallPlant(event.getPlayer(), block, 0, mat -> mat == Material.KELP_PLANT);
        checkBlocksRequiringSupportBelow(event.getPlayer(), block);
        checkAmethystCluster(event.getPlayer(), block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        moveBlocks(event.getBlocks(), event.getDirection());
        regionExpansion.removePlacedBlock(event.getBlock().getRelative(event.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        moveBlocks(event.getBlocks(), event.getDirection());
    }

    private void moveBlocks(List<Block> blocks, BlockFace direction) {
        record Move(Block source, Block target, boolean placed) {}
        var moves = new ArrayList<Move>();
        for (var block : blocks) {
            moves.add(new Move(block, block.getPistonMoveReaction() == PistonMoveReaction.BREAK
                    ? null : block.getRelative(direction), regionExpansion.isPlacedBlock(block)));
        }
        // Read every source before writing any destination: slime/honey chains can overlap.
        for (var move : moves) {
            regionExpansion.removePlacedBlock(move.source());
            if (move.target() != null) regionExpansion.removePlacedBlock(move.target());
        }
        for (var move : moves) {
            if (move.placed() && move.target() != null) regionExpansion.addPlacedBlock(move.target());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeExplodedBlocks(event.blockList(), event.getExplosionResult());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeExplodedBlocks(event.blockList(), event.getExplosionResult());
    }

    private void removeExplodedBlocks(List<Block> blocks, ExplosionResult result) {
        if (result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY) {
            blocks.forEach(regionExpansion::removePlacedBlock);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        regionExpansion.removePlacedBlock(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        var type = event.getNewState().getType();
        if (isAir(type) || type == Material.WATER || type == Material.LAVA) {
            regionExpansion.removePlacedBlock(event.getBlock());
        }
    }

    private static boolean isAir(Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        int growY = event.getLocation().getBlockY();
        for (var state : event.getBlocks()) {
            // Only remove placed blocks at same y level as sapling
            if (state.getLocation().getY() != growY) continue;

            regionExpansion.removePlacedBlock(state.getBlock());
        }
    }

    private void checkTallPlant(Player player, Block block, int num, Predicate<Material> isMaterial) {
        if (num < 26) {
            Block above = block.getRelative(BlockFace.UP);
            if (isMaterial.test(above.getType())) {
                removeBlockWithEvent(player, above);
                checkTallPlant(player, above, num + 1, isMaterial);
            }
        }
    }

    private void checkBlocksRequiringSupportBelow(Player player, Block block) {
        // Check if the block above requires support
        Block above = block.getRelative(BlockFace.UP);
        Material source = above.getType();
        if ((source == Material.MOSS_CARPET || source == Material.AZALEA || source == Material.FLOWERING_AZALEA || source == Material.PINK_PETALS)) {
            removeBlockWithEvent(player, above);
        }
    }

    private void checkAmethystCluster(Player player, Block block) {
        // Check each side
        for (BlockFace face : blockFaces) {
            Block checkedBlock = block.getRelative(face);
            if (Material.AMETHYST_CLUSTER == checkedBlock.getType()) {
                removeBlockWithEvent(player, checkedBlock);
            }
        }
    }

    private void removeBlockWithEvent(Player player, Block checkedBlock) {
        // Emit the event sync so proper block data is available
        var natural = !regionExpansion.isPlacedBlock(checkedBlock);
        Bukkit.getPluginManager().callEvent(new RegionBlockBreakEvent(player, checkedBlock, natural));

        if (!natural) {
            // Remove it a tick later, so we have the info in the bukkit block drop event still
            Bukkit.getRegionScheduler().run(plugin, checkedBlock.getLocation(),
                    (t) -> regionExpansion.removePlacedBlock(checkedBlock));
        }

    }
}
