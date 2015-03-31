package factorization.charge;

import java.io.IOException;

import factorization.api.datahelpers.DataHelper;
import factorization.api.datahelpers.Share;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import factorization.api.Coord;
import factorization.api.DeltaCoord;
import factorization.api.IReflectionTarget;
import factorization.common.BlockIcons;
import factorization.common.FactoryType;
import factorization.shared.BlockClass;
import factorization.shared.Core;
import factorization.shared.NetworkFactorization.MessageType;
import factorization.shared.TileEntityCommon;

public class TileEntityMirror extends TileEntityCommon {
    Coord reflection_target = null;

    //don't save
    public boolean is_lit = false;
    int next_check = 1;
    //don't save, but *do* share w/ client
    public int target_rotation = -99;

    @Override
    public FactoryType getFactoryType() {
        return FactoryType.MIRROR;
    }

    @Override
    public BlockClass getBlockClass() {
        return BlockClass.Machine;
    }

    @Override
    public void putData(DataHelper data) throws IOException {
        if (reflection_target == null) {
            reflection_target = getCoord();
        }
        reflection_target = data.as(Share.VISIBLE, "target").put(reflection_target);
        if (reflection_target.equals(getCoord())) {
            reflection_target = null;
        } else if (data.isReader()) {
            updateRotation();
        }
    }

    @Override
    public void setWorldObj(World w) {
        super.setWorldObj(w);
        if (reflection_target != null) {
            reflection_target.w = w;
        }
    }

    @Override
    public boolean activate(EntityPlayer entityplayer, ForgeDirection side) {
        neighborChanged();
        return false;
    }

    @Override
    public void neighborChanged() {
        next_check = -1;
    }

    int getPower() {
        return 1;
    }

    int clipAngle(int angle) {
        angle = angle % 360;
        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }

    boolean hasSun() {
        boolean raining = getWorldObj().isRaining() && getWorldObj().getBiomeGenForCoords(xCoord, yCoord).rainfall > 0;
        if (raining) return false;
        if (!worldObj.isDaytime()) return false;
        // Used to be able to use Coord.canSeeSky(), but I made my blocks transparent. >_>
        Coord skyLook = new Coord(this);
        int y = yCoord;
        World w = getWorldObj();
        for (int i = y + 1; i < w.getHeight(); i++) {
            skyLook.y = i;
            if (!skyLook.canBeSeenThrough()) {
                return false;
            }
            if (skyLook.getTE(TileEntityMirror.class) != null) {
                return false;
            }
        }
        return true;
    }

    int last_shared = -1;

    void broadcastTargetInfoIfChanged() {
        if (getTargetInfo() != last_shared) {
            broadcastMessage(null, MessageType.MirrorDescription, getTargetInfo());
            last_shared = getTargetInfo();
        }
    }

    int getTargetInfo() {
        return reflection_target == null ? -99 : target_rotation;
    }

    void setRotationTarget(int new_target) {
        if (this.target_rotation != new_target) {
            this.target_rotation = new_target;
        }
    }

    @Override
    public boolean handleMessageFromServer(MessageType messageType, ByteBuf input) throws IOException {
        if (super.handleMessageFromServer(messageType, input)) {
            return true;
        }
        if (messageType == MessageType.MirrorDescription) {
            target_rotation = input.readInt();
            getCoord().redraw();
            gotten_info_packet = true;
            return true;
        }
        return false;
    }
    
    @Override
    protected void onRemove() {
        super.onRemove();
        if (reflection_target == null) {
            return;
        }
        reflection_target.w = worldObj;
        if (worldObj == null) {
            return;
        }
        IReflectionTarget target = reflection_target.getTE(IReflectionTarget.class);
        if (target == null) {
            return;
        }
        if (is_lit) {
            target.addReflector(-getPower());
            is_lit = false;
        }
        reflection_target = null;
    }
    
    @Override
    public void invalidate() {
        super.invalidate();
        if (worldObj != null) {
            onRemove();
        }
    }

    boolean gotten_info_packet = false;
    
    void setNextCheck() {
        next_check = 80 + rand.nextInt(20);
    }
    
    @Override
    public void updateEntity() {
        if (worldObj.isRemote) {
            return;
        }
        if (next_check-- <= 0) {
            try {
                setNextCheck();
                if (reflection_target == null) {
                    findTarget();
                    if (reflection_target == null) {
                        return;
                    }
                } else {
                    reflection_target.setWorld(worldObj);
                }
                //we *do* have a target coord by this point. Is there a TE there tho?
                IReflectionTarget target = null;
                target = reflection_target.getTE(IReflectionTarget.class);
                if (target == null) {
                    if (reflection_target.blockExists()) {
                        reflection_target = null;
                        is_lit = false;
                    }
                    return;
                }
                if (!myTrace(reflection_target.x, reflection_target.z)) {
                    if (is_lit) {
                        is_lit = false;
                        target.addReflector(-getPower());
                        reflection_target = null;
                        setRotationTarget(-99);
                        return;
                    }
                }
    
                if (hasSun() != is_lit) {
                    is_lit = hasSun();
                    target.addReflector(is_lit ? getPower() : -getPower());
                }
            } finally {
                broadcastTargetInfoIfChanged();
            }
        }
    }

    void findTarget() {
        if (reflection_target != null) {
            //make the old target forget about us
            IReflectionTarget target = reflection_target.getTE(IReflectionTarget.class);
            if (target != null) {
                if (is_lit) {
                    target.addReflector(-getPower());
                }
                reflection_target = null;
            }
            is_lit = false;
        }

        int search_distance = 11;
        IReflectionTarget closest = null;
        int last_dist = Integer.MAX_VALUE;
        Coord me = getCoord();
        double maxRadiusSq = 8.9*8.9;
        for (int x = xCoord - search_distance; x <= xCoord + search_distance; x++) {
            for (int z = zCoord - search_distance; z <= zCoord + search_distance; z++) {
                Coord here = new Coord(worldObj, x, yCoord, z);
                IReflectionTarget target = here.getTE(IReflectionTarget.class); // FIXME: Iterate the chunk hash maps instead... get a nice helper function perhaps
                if (target == null) {
                    continue;
                }
                if (!myTrace(x, z)) {
                    continue;
                }
                int new_dist = me.distanceSq(here);
                if (new_dist < last_dist && new_dist <= maxRadiusSq) {
                    last_dist = new_dist;
                    closest = target;
                }
            }
        }
        if (closest != null) {
            reflection_target = closest.getCoord();
            updateRotation();
        } else {
            setRotationTarget(-99);
        }
    }

    void updateRotation() {
        DeltaCoord dc = getCoord().difference(reflection_target);

        int new_target = clipAngle((int) Math.toDegrees(dc.getAngleHorizontal()));
        setRotationTarget(new_target);
    }

    double div(double a, double b) {
        if (b == 0) {
            return Math.signum(a) * 0xFFF;
        }
        return a / b;
    }

    boolean myTrace(double x, double z) {
        x += 0.5;
        z += 0.5;
        double offset_x = x - (xCoord + 0.5), offset_z = z - (zCoord + 0.5);
        double length = Math.hypot(offset_x, offset_z);
        double dx = offset_x / length, dz = offset_z / length;
        x -= dx;
        z -= dz;
        int bx = 0, bz = 0;
        for (int i = 0; i < length; i++) {
            bx = (int) Math.round(x + 0.5) - 1;
            bz = (int) Math.round(z + 0.5) - 1;
            if (bx == xCoord && bz == zCoord) {
                return true;
            }
            final Block b = worldObj.getBlock(bx, yCoord, bz);
            boolean air_like = false;
            if (b == null) {
                air_like = true;
            } else {
                air_like = b.isAir(worldObj, bx, yCoord, bz);
                air_like |= b.getCollisionBoundingBoxFromPool(worldObj, bx, yCoord, bz) == null;
            }
            if (!air_like) {
                return false;
            }
            x -= dx;
            z -= dz;
        }
        return false;
    }

    @Override
    public boolean isBlockSolidOnSide(int side) {
        return false;
    }
    
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(ForgeDirection dir) {
        return BlockIcons.mirror_front;
    }
    
    @Override
    public ItemStack getDroppedBlock() {
        return new ItemStack(Core.registry.mirror);
    }
}
