package factorization.sockets.fanturpeller;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_LIGHTING;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBucket;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.BlockFluidFinite;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import factorization.api.Coord;
import factorization.api.FzOrientation;
import factorization.api.Quaternion;
import factorization.common.BlockIcons;
import factorization.common.FactoryType;
import factorization.servo.ServoMotor;
import factorization.shared.Core;
import factorization.shared.FzUtil;
import factorization.shared.ObjectModel;
import factorization.sockets.ISocketHolder;

public class PumpLiquids extends BufferedFanturpeller {
    private interface PumpAction {
        void suckIn();
        FluidStack drainBlock(PumpCoord probe, boolean doDrain);
        void pumpOut();
    }
    
    static final class PumpCoord {
        final int x, y, z;
        final PumpCoord parent;
        final short pathDistance;
        PumpCoord(Coord at, PumpCoord parent, int pathDistance) {
            x = at.x;
            y = at.y;
            z = at.z;
            this.pathDistance = (short) pathDistance;
            this.parent = parent;
        }
        
        PumpCoord(PumpCoord parent, ForgeDirection d) {
            this.x = parent.x + d.offsetX;
            this.y = parent.y + d.offsetY;
            this.z = parent.z + d.offsetZ;
            this.pathDistance = (short) (parent.pathDistance + 1);
            this.parent = parent;
        }
        
        
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof PumpCoord) {
                PumpCoord o = (PumpCoord) obj;
                return o.x == x && o.y == y && o.z == z;
            }
            return false;
        }
        
        @Override
        public int hashCode() {
            return (((x * 11) % 71) << 7) + ((z * 7) % 479) + y; //TODO: This hashcode is probably terrible.
        }
        
        boolean verifyConnection(PumpAction pump, World w) {
            PumpCoord here = parent;
            while (here != null) {
                if (pump.drainBlock(here, false) == null) return false;
                here = here.parent;
            }
            return true;
        }
    }
    
    final static int max_pool = (16*16*24)*12*12;
    
    private class Drainer implements PumpAction {
        // o <-- o <-- o <-- o <-- o
        final ArrayDeque<PumpCoord> frontier = new ArrayDeque();
        final HashSet<PumpCoord> visited = new HashSet();
        final PriorityQueue<PumpCoord> queue = new PriorityQueue<PumpCoord>(128, getComparator());
        
        Comparator<PumpCoord> getComparator() {
            return new Comparator<PumpCoord>() {
                @Override
                public int compare(PumpCoord a, PumpCoord b) {
                    // If we're draining, we want the furthest & highest liquid
                    if (a.y == b.y) { 
                        return b.pathDistance - a.pathDistance;
                    }
                    return b.y - a.y;
                }
            };
        }
        
        final Coord start;
        final Fluid targetFluid;
        
        int delay; // we wait this long before reconstruction
        
        Drainer(Coord start, Fluid targetFluid) {
            this.start = start;
            this.targetFluid = targetFluid;
            reset();
        }
        
        void reset() {
            visited.clear();
            queue.clear();
            frontier.clear();
            PumpCoord seed = new PumpCoord(start, null, 0);
            frontier.add(seed);
            visited.add(seed);
            queue.add(seed);
            delay = 20*3;
        }
        
        @Override
        public FluidStack drainBlock(PumpCoord probe, boolean doDrain) {
            return FzUtil.drainSpecificBlockFluid(worldObj, probe.x, probe.y, probe.z, doDrain, targetFluid);
        }
        
        FluidStack probeAbove(PumpCoord probe) {
            return FzUtil.drainSpecificBlockFluid(worldObj, probe.x, probe.y + 1, probe.z, false, targetFluid);
        }
        
        boolean updateFrontier() {
            if (visited.size() > max_pool) return false;
            if (frontier.isEmpty()) return false;
            Coord probe = new Coord(worldObj, 0, 0, 0);
            int maxHeight = getMaxHeight();
            int maxDistance = getMaxDistance();
            for (int amount = Math.max(frontier.size(), 1024); amount > 0; amount--) {
                PumpCoord pc = frontier.poll();
                if (pc == null) return true;
                if (pc.y >= maxHeight) continue;
                if (pc.pathDistance >= maxDistance) continue;
                boolean orig_is_liquid = drainBlock(pc, false) != null;
                if (!orig_is_liquid) {
                    continue; //...oops!
                }
                for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                    if (!isSucking && pc.y + dir.offsetY >= maxHeight) continue;
                    PumpCoord at = new PumpCoord(pc, dir);
                    probe.set(worldObj, at.x, at.y, at.z);
                    if (visited.contains(at) || !probe.blockExists()) continue;
                    boolean is_liquid = drainBlock(at, false) != null;
                    boolean replaceable = probe.isReplacable();
                    if (!is_liquid && !replaceable) continue;
                    if (!is_liquid && replaceable && isLiquid(probe)) continue; // It's a different liquid.
                    visited.add(at); // Don't revisit this place.
                    found(replaceable, is_liquid, at);
                }
            }
            return true;
        }
        
        protected void found(boolean replaceable, boolean is_liquid, PumpCoord at) {
            if (is_liquid) {
                queue.add(at); // We can continue iteration here
                frontier.add(at); // It can be sucked up
            }
        }

        @Override
        public void suckIn() {
            if (isSucking) return; //don't run backwards
            if (buffer.getFluidAmount() > 0) return;
            if (updateFrontier()) return;
            if (delay > 0) {
                delay--;
                return;
            }
            delay = 20;
            PumpCoord pc = queue.poll();
            if (pc == null || !pc.verifyConnection(this, worldObj)) {
                reset();
                return;
            }
            if (probeAbove(pc) != null) {
                // Fluid (likely water) has refilled in above us
                reset();
            } else {
                buffer.setFluid(drainBlock(pc, true));
            }
        }

        @Override
        public void pumpOut() { }
        
        int getMaxHeight() {
            return worldObj.getHeight();
        }
        
        int getMaxDistance() {
            return 64*target_speed;
        }
    }
    
    private class Flooder extends Drainer {
        Flooder(Coord start, Fluid targetFluid) {
            super(start, targetFluid);
        }
        
        Comparator<PumpCoord> getComparator() {
            return new Comparator<PumpCoord>() {
                @Override
                public int compare(PumpCoord a, PumpCoord b) {
                    // If we're flooding, we want the furthest & lowest liquid
                    if (a.y == b.y) {
                        if (a.pathDistance == b.pathDistance) {
                            return 0;
                        } else if (a.pathDistance > b.pathDistance) {
                            return 1;
                        } else {
                            return -1;
                        }
                    } else if (a.y > b.y) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
            };
        }
        
        @Override
        void reset() {
            super.reset();
            queue.add(frontier.peek());
            // We force the block in front to be valid because we absolutely need to fill it up.
            // We don't need to worry about someone changing it to stone if we check it during normal processing.
        }
        
        @Override
        protected void found(boolean replaceable, boolean is_liquid, PumpCoord at) {
            if (replaceable && !is_liquid) {
                queue.add(at);
            } else if (is_liquid) {
                frontier.add(at);
            }
        }
        
        @Override
        public void suckIn() { }
        
        @Override
        public void pumpOut() {
            if (isSucking) return; //don't run backwards
            if (buffer.getFluidAmount() < BUCKET) return;
            FluidStack fs = buffer.getFluid();
            if (fs == null) return;
            Fluid fluid = fs.getFluid();
            if (!fluid.canBePlacedInWorld()) {
                destinationAction = new TankPumper(); // This is okay?
                return;
            }
            if (delay > 0) {
                delay--;
                return;
            }
            delay = 20;
            if (updateFrontier()) return;
            for (int i = 0; i < 16; i++) {
                PumpCoord pc = queue.poll();
                if (pc == null || !pc.verifyConnection(this, worldObj)) {
                    reset();
                    return;
                }
                if (placeFluid(pc, fluid)) {
                    //Have we opened up new frontiers? (Hint: probably)
                    frontier.add(pc);
                    break;
                }
            }
        }
        
        private Coord at = new Coord(worldObj, 0, 0, 0);
        boolean placeFluid(PumpCoord pc, Fluid fluid) {
            at.w = worldObj;
            at.x = pc.x;
            at.y = pc.y;
            at.z = pc.z;
            if (!at.isReplacable()) return false;
            Block block = fluid.getBlock();
            if (block == null) return false;
            if (drainBlock(pc, false) != null) return false;
            if (block == Blocks.water) block = Blocks.flowing_water;
            else if (block == Blocks.lava) block = Blocks.flowing_lava;
            
            if (block == Blocks.flowing_water) {
                ((ItemBucket) Items.water_bucket).tryPlaceContainedLiquid(at.w, at.x, at.y, at.z);
            } else {
                at.setIdMd(block, block instanceof BlockFluidFinite ? 0xF : 0, true);
            }
            buffer.setFluid(null);
            at.notifyBlockChange();
            return true;
        }
        
        @Override
        int getMaxHeight() {
            return yCoord + 1 + 3*target_speed;
        }
    }
    
    private class TankPumper implements PumpAction {
        @Override
        public void suckIn() {
            if (buffer.getFluidAmount() >= BUCKET) return;
            Coord at = new Coord(PumpLiquids.this);
            at.adjust(sourceDirection);
            IFluidHandler te = at.getTE(IFluidHandler.class);
            if (te == null) {
                return; //Shouldn't happen?
            }
            FluidStack rep = null;
            if (buffer.getFluidAmount() > 0) {
                rep = buffer.getFluid().copy();
                rep.amount = Math.min(50, BUCKET - buffer.getFluidAmount());
                buffer.fill(te.drain(destinationDirection, rep, true), true);
            } else {
                FluidTankInfo[] tanks = te.getTankInfo(destinationDirection);
                if (tanks == null || tanks.length == 0) return;
                for (FluidTankInfo tank : tanks) {
                    if (tank.fluid == null) continue;
                    FluidStack fs = te.drain(destinationDirection, BUCKET, true);
                    if (fs != null) {
                        buffer.setFluid(fs);
                        break;
                    }
                }
            }
        }

        @Override
        public void pumpOut() {
            if (buffer.getFluidAmount() <= 0) return;
            Coord at = new Coord(PumpLiquids.this);
            at.adjust(destinationDirection);
            IFluidHandler te = at.getTE(IFluidHandler.class);
            if (te == null) {
                return; //Really, it shoudln't happen.
            }
            int origSize = buffer.getFluidAmount();
            FluidStack offering = buffer.getFluid().copy();
            offering.amount = Math.min(10, offering.amount);
            int usage = te.fill(sourceDirection, offering, true);
            buffer.drain(usage, true);
        }

        @Override
        public FluidStack drainBlock(PumpCoord probe, boolean doDrain) { return null; }
        
    }

    transient PumpAction sourceAction, destinationAction;
    transient ForgeDirection sourceDirection, destinationDirection;
    
    @Override
    public FactoryType getFactoryType() {
        return FactoryType.SOCKET_PUMP;
    }
    
    @Override
    protected void fanturpellerUpdate(ISocketHolder socket, Coord coord, boolean powered, boolean neighbor_changed) {
        if (worldObj.isRemote){
            return;
        }
        if (isSucking) {
            sourceDirection = facing;
            destinationDirection = facing.getOpposite();
        } else {
            sourceDirection = facing.getOpposite();
            destinationDirection = facing;
        }
        if (neighbor_changed) {
            coord.adjust(facing.getOpposite());
            if (sourceAction instanceof TankPumper ^ hasTank(coord)) {
                sourceAction = null;
            }
            if (sourceAction instanceof Drainer ^ isLiquid(coord)) {
                sourceAction = null;
            }
            coord.adjust(facing);
            coord.adjust(facing);
            if (destinationAction instanceof TankPumper ^ hasTank(coord)) {
                destinationAction = null;
            }
            if (destinationAction instanceof Flooder ^ isLiquid(coord)) {
                destinationAction = null;
            }
            coord.adjust(facing.getOpposite());
        }
        if (!shouldDoWork()) {
            updateDestination(coord);
            if (sourceAction == null) {
                updateSource(coord);
            }
        } else {
            updateSource(coord);
            updateDestination(coord);
        }
    }

    void updateSource(Coord coord) {
        if (sourceAction == null) {
            coord.adjust(facing.getOpposite());
            if (hasTank(coord)) {
                sourceAction = new TankPumper();
            } else if (isLiquid(coord)) {
                final Coord c = new Coord(this).add(sourceDirection);
                sourceAction = new Drainer(c, c.getFluid());
            }
            coord.adjust(facing);
        } else {
            sourceAction.suckIn();
        }
    }

    void updateDestination(Coord coord) {
        if (destinationAction == null) {
            coord.adjust(facing);
            if (hasTank(coord)) {
                destinationAction = new TankPumper();
            } else if ((isLiquid(coord) || coord.isReplacable()) && buffer.getFluidAmount() > 0) {
                final Coord c = new Coord(this).add(destinationDirection);
                destinationAction = new Flooder(c, buffer.getFluid().getFluid());
            }
            coord.adjust(facing.getOpposite());
        } else {
            destinationAction.pumpOut();
        }
    }
    
    @Override
    protected boolean isSafeToDiscard() {
        return buffer.getFluidAmount() == 0;
    }
    
    String easyName(Object obj) {
        if (obj == null) return "None";
        return obj.getClass().getSimpleName();
    }
    
    @Override
    public String getInfo() {
        FluidStack fs = buffer.getFluid();
        String fluid;
        if (fs == null) {
            fluid = "";
        } else {
            fluid = "\n" + fs.amount + "mB of " + fs.getFluid().getName();
        }
        float targetSpeed = getTargetSpeed();
        String speed = "";
        if (Math.abs(targetSpeed) > 1) {
            speed = "\nSpeed: " + (int)(100*fanω/targetSpeed) + "%";
        }
        return easyName(sourceAction) + 
                " -> " + easyName(destinationAction) +
                fluid + speed;
    }
    
    @Override
    protected boolean shouldFeedJuice() {
        return sourceAction != null || destinationAction != null;
    }

    @Override
    int getRequiredCharge() {
        return (int)getTargetSpeed();
    }
    
    @SideOnly(Side.CLIENT)
    private static ObjectModel corkscrew;
    
    @Override
    @SideOnly(Side.CLIENT)
    public void representYoSelf() {
        super.representYoSelf();
        corkscrew = new ObjectModel(Core.getResource("models/corkscrew.obj"));
    }
    
    @Override
    @SideOnly(Side.CLIENT)
    public void renderTesr(ServoMotor motor, float partial) {
        float d = 0.5F;
        GL11.glTranslatef(d, d, d);
        Quaternion.fromOrientation(FzOrientation.fromDirection(facing.getOpposite())).glRotate();
        float turn = scaleRotation(FzUtil.interp(prevFanRotation, fanRotation, partial));
        GL11.glRotatef(turn, 0, 1, 0);
        float sd = motor == null ? -2F/16F : 3F/16F;
        sd += -7F/16F;
        GL11.glTranslatef(0, sd, 0);
        
        
        float s = 12F/16F;
        if (motor != null) {
            s = 10F/16F;
            GL11.glTranslatef(0, -3F/16F, 0);
        }
        GL11.glScalef(s, 1, s);
        
        TextureManager tex = Minecraft.getMinecraft().renderEngine;
        tex.bindTexture(Core.blockAtlas);
        glEnable(GL_LIGHTING);
        glDisable(GL11.GL_CULL_FACE);
        glEnable(GL12.GL_RESCALE_NORMAL);
        corkscrew.render(BlockIcons.socket$corkscrew);
        glEnable(GL11.GL_CULL_FACE);
        glEnable(GL_LIGHTING);
    }
}
