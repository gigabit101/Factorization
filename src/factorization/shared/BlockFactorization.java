package factorization.shared;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityDiggingFX;
import net.minecraft.client.renderer.texture.IconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Icon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import factorization.api.Coord;
import factorization.api.IFactoryType;
import factorization.common.BlockIcons;
import factorization.common.FactoryType;
import factorization.common.Registry;
import factorization.notify.Notify;
import factorization.shared.NetworkFactorization.MessageType;
import factorization.weird.TileEntityDayBarrel;
import factorization.weird.TileEntityDayBarrel.Type;

public class BlockFactorization extends BlockContainer {
    public boolean fake_normal_render = false;
    public BlockFactorization(int id) {
        super(id, Core.registry.materialMachine);
        setHardness(2.0F);
        setResistance(5);
        setLightOpacity(0);
        canBlockGrass[id] = true;
        setTickRandomly(false);
    }

    @Override
    public TileEntity createNewTileEntity(World world) {
        //The TileEntity needs to be set by the item when the block is placed.
        //Originally I returned null here, but we're now returning this handy generic TE.
        //This is because portalgun relies on this to make a TE that won't drop anything when it's moving it.
        //But when this returned null, it wouldn't remove the real TE. So, the tile entity was both having its block broken, and being moved.
        //Returning a generic TE won't be an issue for us as we always use coord.getTE, and never assume, right?
        //We could possibly have our null TE remove itself.
        return new TileEntityFzNull();
    }

    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World world, int x, int y, int z) {
        TileEntityCommon tec = new Coord(world, x, y, z).getTE(TileEntityCommon.class);
        if (tec == null) {
            return null;
        }
        return tec.getPickedBlock();
    }

    @Override
    public boolean hasTileEntity() {
        return true;
    }

    @Override
    public boolean isBlockSolid(IBlockAccess world, int x, int y, int z, int side) {
        TileEntity t = world.getBlockTileEntity(x, y, z);
        if (t == null || !(t instanceof TileEntityCommon)) {
            return false;
        }
        TileEntityCommon te = (TileEntityCommon) t;
        return te.isBlockSolidOnSide(side);
    }
    
    @Override
    public boolean isBlockSolidOnSide(World world, int x, int y, int z, ForgeDirection side) {
        return isBlockSolid(world, x, y, z, side.ordinal());
    }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, int l) {
        int md = w.getBlockMetadata(x, y, z);
        TileEntity ent = w.getBlockTileEntity(x, y, z);
        if (ent == null) {
            return;
        }
        if (ent instanceof TileEntityCommon) {
            TileEntityCommon tec = (TileEntityCommon) ent;
            tec.neighborChanged();
        }
    }

    //TODO: Ctrl/alt clicking!

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer entityplayer,
            int side, float vecx, float vecy, float vecz) {
        // right click
        Coord here = new Coord(world, x, y, z);
        if (here.getTE() == null && world.isRemote) {
            Core.network.broadcastMessage(null, here, MessageType.DescriptionRequest);
        }
        if (entityplayer.isSneaking()) {
            ItemStack cur = entityplayer.getCurrentEquippedItem();
            if (cur == null || cur.getItem() != Core.registry.logicMatrixProgrammer) {
                return false;
            }
        }

        TileEntityCommon t = here.getTE(TileEntityCommon.class);

        if (t != null) {
            return t.activate(entityplayer, ForgeDirection.getOrientation(side));
        } else {
            //info message
            if (world.isRemote) {
                if (here.getTE() == null) {
                    //we may be about to get a GUI, incidentally...
                    Core.network.broadcastMessage(null, here, MessageType.DescriptionRequest);
                    return false;
                }
                return false; //...?
            }
            entityplayer.addChatMessage("This block is missing its TileEntity, possibly due to a bug in Factorization.");
            entityplayer.addChatMessage("The block and its contents can not be recovered without cheating.");
            return true;
        }
    }

    @Override
    public void onBlockClicked(World world, int x, int y, int z,
            EntityPlayer entityplayer) {
        // left click

        if (world.isRemote) {
            return;
        }

        TileEntity t = world.getBlockTileEntity(x, y, z);
        if (t instanceof TileEntityFactorization) {
            ((TileEntityFactorization) t).click(entityplayer);
        }
    }
    
    @Override
    public void registerIcons(IconRegister reg) {
        FactorizationTextureLoader.register(reg, BlockIcons.class, null, "factorization:");
        Core.proxy.texturepackChanged();
    }
    
    static public Icon force_texture = null;

    @Override
    public Icon getBlockTexture(IBlockAccess w, int x, int y, int z, int side) {
        // Used for in-world rendering. Takes 'active' into consideration.
        if (force_texture != null) {
            return force_texture;
        }
        TileEntity t = w.getBlockTileEntity(x, y, z);
        if (t instanceof TileEntityCommon) {
            return ((TileEntityCommon) t).getIcon(ForgeDirection.getOrientation(side));
        }
        return BlockIcons.error;
    }

    private Icon tempParticleIcon = null;
    
    @Override
    public Icon getIcon(int side, int md) {
        if (tempParticleIcon != null) {
            Icon ret = tempParticleIcon;
            tempParticleIcon = null;
            return ret;
        }
        // This shouldn't be called when rendering in the world.
        // Is used for inventory!
        FactoryType ft = FactoryType.fromMd(md);
        if (ft == null) {
            return BlockIcons.default_icon;
            //return BlockIcons.error;
        }
        TileEntityCommon rep = ft.getRepresentative();
        if (rep == null) {
            return BlockIcons.error;
        }
        return rep.getIcon(ForgeDirection.getOrientation(side));
    }
    
    @Override
    public int damageDropped(int i) {
        return i;
    }

    @Override
    public int quantityDropped(int meta, int fortune, Random random) {
        return 1;
    }
    
    LinkedList<TileEntityCommon> destroyed_tes = new LinkedList<TileEntityCommon>();
    
    @Override
    public void breakBlock(World w, int x, int y, int z, int id, int md) {
        Coord here = new Coord(w, x, y, z);
        TileEntityCommon te = here.getTE(TileEntityCommon.class);
        if (te != null) {
            te.onRemove();
            destroyed_tes.add(te);
        }
        super.breakBlock(w, x, y, z, id, md); //Just removes the TE; does nothing else.
    }
    
    @Override
    public boolean removeBlockByPlayer(World world, EntityPlayer player, int x, int y, int z) {
        Coord here = new Coord(world, x, y, z);
        TileEntityCommon tec = here.getTE(TileEntityCommon.class);
        if (tec == null) {
            Notify.send(player, here, "No TileEntity!");
            return super.removeBlockByPlayer(world, player, x, y, z);
        }
        return tec.removeBlockByPlayer(player);
    }
    

    @Override
    public ArrayList<ItemStack> getBlockDropped(World world, int X, int Y, int Z, int md, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        Coord here = new Coord(world, X, Y, Z);
        TileEntityCommon f = here.getTE(TileEntityCommon.class);
        if (f == null) {
            Iterator<TileEntityCommon> it = destroyed_tes.iterator();
            TileEntityCommon destroyedTE = null;
            while (it.hasNext()) {
                TileEntityCommon tec = it.next();
                if (tec.getCoord().equals(here)) {
                    destroyedTE  = tec;
                    it.remove();
                }
            }
            if (destroyedTE == null) {
                Core.logWarning("No IFactoryType TE behind block that was destroyed, and nothing saved!");
                return ret;
            }
            Coord destr = destroyedTE.getCoord();
            if (!destr.equals(here)) {
                Core.logWarning("Last saved destroyed TE wasn't for this location");
                destroyedTE = null;
                return ret;
            }
            if (!(destroyedTE instanceof IFactoryType)) {
                Core.logWarning("TileEntity isn't an IFT! It's " + here.getTE());
                destroyedTE = null;
                return ret;
            }
            f = destroyedTE;
            destroyedTE = null;
        }
        ItemStack is = f.getDroppedBlock();
        ret.add(is);
        return ret;
    }

    @Override
    public void addCreativeItems(ArrayList itemList) {
        if (this != Core.registry.factory_block) {
            return;
        }
        Registry reg = Core.registry;
        //common
        
        itemList.add(reg.stamper_item);
        itemList.add(reg.packager_item);
        itemList.add(reg.slagfurnace_item);
        itemList.add(reg.parasieve_item);

        //electric
        //itemList.add(reg.battery_item_hidden);
        if (reg.battery != null) {
            //These checks are for buildcraft, which is hatin'.
            itemList.add(new ItemStack(reg.battery, 1, 2));
        }
        itemList.add(reg.caliometric_burner_item);
        itemList.add(reg.solarboiler_item);
        itemList.add(reg.steamturbine_item);
        //itemList.add(reg.mirror_item_hidden);
        if (reg.mirror != null) {
            itemList.add(new ItemStack(reg.mirror));
        }
        itemList.add(reg.heater_item);
        itemList.add(reg.leadwire_item);
        itemList.add(reg.grinder_item);
        itemList.add(reg.mixer_item);
        itemList.add(reg.crystallizer_item);
        itemList.add(reg.leydenjar_item);
        if (reg.leydenjar_item_full != null) {
            itemList.add(reg.leydenjar_item_full);
        }

        itemList.add(reg.greenware_item);
        
        if (reg.rocket_engine != null) {
            itemList.add(new ItemStack(reg.rocket_engine));
        }
        
        //dark
        itemList.add(reg.router_item);
        itemList.add(reg.empty_socket_item);
        itemList.add(reg.servorail_item);
        itemList.add(reg.lamp_item);
        itemList.add(reg.compression_crafter_item);
        
        //Barrels
        if (reg.daybarrel != null) {
            //itemList.add(new ItemStack(reg.daybarrel));
            int count = 0;
            int added = 0;
            
            int types = TileEntityDayBarrel.Type.TYPE_COUNT - 1;
            Calendar cal = Calendar.getInstance();
            int doy = cal.get(Calendar.DAY_OF_YEAR) - 1 /* start at 0, not 1 */;
            int wood_of_the_day = doy % (types - 1);
            for (ItemStack is : TileEntityDayBarrel.barrel_items) {
                count++;
                if (count > wood_of_the_day*types + 1 && count <= (wood_of_the_day+1)*types + 1) {
                    itemList.add(is);
                }
            }
            //ugly; the first item in the list is the creative barrel; it oughta be separate
            itemList.add(TileEntityDayBarrel.barrel_items.get(0));
        }
    }

    @Override
    public void getSubBlocks(int par1, CreativeTabs par2CreativeTabs, List par3List) {
        if (this != Core.registry.factory_block) {
            return;
        }
        Core.addBlockToCreativeList(par3List, this);
    }

    @Override
    public boolean canConnectRedstone(IBlockAccess world, int x, int y, int z, int dir) {
        TileEntity te = world.getBlockTileEntity(x, y, z);
        if (te instanceof TileEntityCommon) {
            TileEntityCommon tec = (TileEntityCommon) te;
            return tec.getFactoryType().connectRedstone();
        }
        return false;
    }

    @Override
    public boolean isBlockNormalCube(World world, int i, int j, int k) {
        return BlockClass.get(world.getBlockMetadata(i, j, k)).isNormal();
    }

    @Override
    public int getFlammability(IBlockAccess world, int x, int y, int z,
            int md, ForgeDirection face) {
        if (BlockClass.Barrel.md == md) {
            return 20;
        }
        return 0;
    }

    @Override
    public boolean isFlammable(IBlockAccess world, int x, int y, int z, int metadata, ForgeDirection face) {
        //Not really. But this keeps fire rendering.
        //return true;
        return BlockClass.Barrel.md == metadata;
    }

    //Lightair/lamp stuff

    @Override
    public int getLightValue(IBlockAccess world, int x, int y, int z) {
        int md = world.getBlockMetadata(x, y, z);
        BlockClass c = BlockClass.get(md);
        if (c == BlockClass.MachineLightable) {
            TileEntity te = world.getBlockTileEntity(x, y, z);
            if (te instanceof TileEntityFactorization) {
                if (((TileEntityFactorization) te).draw_active == 0) {
                    return BlockClass.Machine.lightValue;
                }
                return c.lightValue;
            }
        }
        if (c == BlockClass.MachineDynamicLightable) {
            TileEntity te = world.getBlockTileEntity(x, y, z);
            if (te instanceof TileEntityCommon) {
                return ((TileEntityCommon) te).getDynamicLight();
            }
        }
        return BlockClass.get(md).lightValue;
    }

    @Override
    public float getBlockHardness(World w, int x, int y, int z) {
        BlockClass bc = BlockClass.get(w.getBlockMetadata(x, y, z));
        return bc.hardness;
    }

    //smack these blocks up
    @Override
    public MovingObjectPosition collisionRayTrace(World w, int x, int y, int z,
            Vec3 startVec, Vec3 endVec) {
        TileEntityCommon tec = new Coord(w, x, y, z).getTE(TileEntityCommon.class);
        if (tec == null) {
            return super.collisionRayTrace(w, x, y, z, startVec, endVec);
        }
        return tec.collisionRayTrace(startVec, endVec);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        TileEntityCommon tec = new Coord(w, x, y, z).getTE(TileEntityCommon.class);
        setBlockBounds(0, 0, 0, 1, 1, 1);
        if (tec == null) {
            return super.getCollisionBoundingBoxFromPool(w, x, y, z);
        }
        return tec.getCollisionBoundingBoxFromPool();
    }
    
    @Override
    public void addCollisionBoxesToList(World w, int x, int y, int z, AxisAlignedBB aabb, List list, Entity entity) {
        TileEntityCommon tec = new Coord(w, x, y, z).getTE(TileEntityCommon.class);
        Block test = w.isRemote ? Core.registry.clientTraceHelper : Core.registry.serverTraceHelper;
        if (tec == null || !tec.addCollisionBoxesToList(test, aabb, list, entity)) {
            super.addCollisionBoxesToList(w, x, y, z, aabb, list, entity);
        }
    }
    
    @Override
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World w, int x, int y, int z) {
        TileEntity te = w.getBlockTileEntity(x, y, z);
        if (te instanceof TileEntityCommon) {
            TileEntityCommon tec = (TileEntityCommon) te;
            if (tec.getFactoryType() == FactoryType.EXTENDED) {
                AxisAlignedBB ret = tec.getCollisionBoundingBoxFromPool();
                if (ret != null) {
                    return ret;
                }
            }
        }
        return super.getSelectedBoundingBoxFromPool(w, x, y, z);
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        TileEntity te = w.getBlockTileEntity(x, y, z);
        if (te == null || !(te instanceof TileEntityCommon)) {
            setBlockBounds(0, 0, 0, 1, 1, 1);
            return;
        }
        ((TileEntityCommon) te).setBlockBounds(this);
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public int getRenderType() {
        if (fake_normal_render) {
            return 0;
        }
        return Core.factory_rendertype;
    }

    public static final float lamp_pad = 1F / 16F;

    @Override
    public boolean canProvidePower() {
        return true;
    }
    
    @Override
    public void updateTick(World w, int x, int y, int z, Random rand) {
        w.notifyBlockChange(x, y, z, blockID);
    }
    
    
    //Maybe we should only give weak power?
    @Override
    public int isProvidingStrongPower(IBlockAccess w, int x, int y, int z, int side) {
        /*if (side < 2) {
            return 0;
        }
        TileEntity te = w.getBlockTileEntity(x, y, z);
        if (te instanceof TileEntityCommon) {
            return ((TileEntityCommon) te).power() ? 15 : 0;
        }*/
        return 0;
    }
    
    @Override
    public int isProvidingWeakPower(IBlockAccess w, int x, int y, int z, int side) {
        TileEntity te = w.getBlockTileEntity(x, y, z);
        if (te instanceof TileEntityCommon) {
            return ((TileEntityCommon) te).power() ? 15 : 0;
        }
        return 0;
    }

    @Override
    //ser-ver ser-ver
    public void randomDisplayTick(World w, int x, int y, int z, Random rand) {
        Core.proxy.randomDisplayTickFor(w, x, y, z, rand);
    }

    
    public static int CURRENT_PASS = 0;
    @Override
    public boolean canRenderInPass(int pass) {
        //return pass == 0;
        CURRENT_PASS = pass;
        return pass == 0 || pass == 1;
    }

    @Override
    // -- hello server asshole
    public int getRenderBlockPass() {
        return 1;
    }

    @Override
    public boolean isAirBlock(World world, int x, int y, int z) {
        return false;
    }
    
    public static int sideDisable = 0;
    
    @Override
    public boolean shouldSideBeRendered(IBlockAccess iworld, int x, int y, int z, int side) {
        if (sideDisable != 0) {
            return (sideDisable & (1 << side)) == 0; 
        }
        return super.shouldSideBeRendered(iworld, x, y, z, side);
    }
    
    @Override
    @SideOnly(Side.CLIENT)
    public boolean addBlockHitEffects(World worldObj, MovingObjectPosition target, EffectRenderer effectRenderer) {
        Coord here = new Coord(worldObj, target.blockX, target.blockY, target.blockZ);
        TileEntityCommon tec = here.getTE(TileEntityCommon.class);
        tempParticleIcon = (tec == null) ? BlockIcons.default_icon : tec.getIcon(ForgeDirection.getOrientation(target.sideHit));
        return false;
    }
    
    static final Random rand = new Random();
    
    @Override
    @SideOnly(Side.CLIENT)
    public boolean addBlockDestroyEffects(World world, int x, int y, int z, int meta, EffectRenderer effectRenderer) {
        TileEntity te = world.getBlockTileEntity(x, y, z);
        if (!(te instanceof TileEntityCommon)) {
            return false;
        }
        TileEntityCommon tec = (TileEntityCommon) te;
        Icon theIcon = (tec == null) ? BlockIcons.default_icon : tec.getIcon(ForgeDirection.DOWN);
        
        //copied & modified from EffectRenderer.addBlockDestroyEffects
        byte b0 = 4;
        for (int j1 = 0; j1 < b0; ++j1)
        {
            for (int k1 = 0; k1 < b0; ++k1)
            {
                for (int l1 = 0; l1 < b0; ++l1)
                {
                    double d0 = (double)x + ((double)j1 + 0.5D) / (double)b0;
                    double d1 = (double)y + ((double)k1 + 0.5D) / (double)b0;
                    double d2 = (double)z + ((double)l1 + 0.5D) / (double)b0;
                    EntityDiggingFX fx = (new EntityDiggingFX(world, d0, d1, d2, d0 - (double)x - 0.5D, d1 - (double)y - 0.5D, d2 - (double)z - 0.5D, this, meta)).applyColourMultiplier(x, y, z);
                    fx.setParticleIcon(theIcon);
                    effectRenderer.addEffect(fx);
                }
            }
        }
        
        return true;
    }
    
    @Override
    public boolean rotateBlock(World worldObj, int x, int y, int z, ForgeDirection axis) {
        final Coord at = new Coord(worldObj, x, y, z);
        TileEntityCommon tec = at.getTE(TileEntityCommon.class);
        if (tec == null) {
            return false;
        }
        boolean suc = tec.rotate(axis);
        if (suc) {
            at.markBlockForUpdate();
        }
        return suc;
    }
    
    @Override
    public ForgeDirection[] getValidRotations(World worldObj, int x, int y, int z) {
        TileEntityCommon tec = new Coord(worldObj, x, y, z).getTE(TileEntityCommon.class);
        if (tec == null) {
            return TileEntityCommon.empty_rotation_array;
        }
        return tec.getValidRotations();
    }
    
    @Override
    public boolean hasComparatorInputOverride() {
        return true;
    }
    
    @Override
    public int getComparatorInputOverride(World world, int x, int y, int z, int side) {
        TileEntityCommon tec = new Coord(world, x, y, z).getTE(TileEntityCommon.class);
        if (tec == null) {
            return 0;
        }
        return tec.getComparatorValue(ForgeDirection.getOrientation(side));
    }
    
    @Override
    public boolean getBlocksMovement(IBlockAccess world, int x, int y, int z) {
        int md = world.getBlockMetadata(x, y, z);
        if (md == BlockClass.Wire.md) {
            return true;
        }
        return false;
    }
    
    @Override
    public void onNeighborTileChange(World world, int x, int y, int z,
            int tilex, int tiley, int tilez) {
        TileEntity te = world.getBlockTileEntity(x, y, z);
        if (te instanceof TileEntityCommon) {
            ((TileEntityCommon) te).onNeighborTileChanged(tilex, tiley, tilez);
        }
    }
}