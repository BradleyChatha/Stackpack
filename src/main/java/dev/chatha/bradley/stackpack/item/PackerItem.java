package dev.chatha.bradley.stackpack.item;

import dev.chatha.bradley.stackpack.StackPackMod;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.List;

enum PackerMode
{
    CONFIGURE   (0, "Configure"),
    PACK        (1, "Pack"),
    UNPACK      (2, "Unpack");

    public static final PackerMode DEFAULT = PackerMode.CONFIGURE;
    public static final PackerMode MAX     = PackerMode.UNPACK;

    private final int _value;
    private final String _name;
    PackerMode(final int value, final String name)
    {
        this._value = value;
        this._name = name;
    }

    public int getValue() { return this._value; }
    public String getName()
    {
        return this._name;
    }

    public static PackerMode fromValue(final int value)
    {
        switch(value)
        {
            default:
            case 0: return CONFIGURE;
            case 1: return PACK;
            case 2: return UNPACK;
        }
    }
}

public class PackerItem extends Item
{
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAX_PACK_SIZE = 64 * 64;

    private static final String NBT_MODE             = "mode";
    private static final String NBT_CONFIGURED_ITEM  = "item";
    private static final String NBT_PACKED           = "size";

    public PackerItem()
    {
        super(
            new Properties()
                .group(StackPackMod.GROUP)
                .maxStackSize(1)
                .setNoRepair()
        );
        super.setRegistryName("stackpack", "packer");
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<ITextComponent> tooltip, ITooltipFlag flagIn)
    {
        if(worldIn.isRemote())
            return;

        final CompoundNBT nbt = stack.getOrCreateTag();
        I18n.format("tooltip.stackpack.packer.mode",  PackerMode.fromValue(nbt.getInt(NBT_MODE)).getName());
        I18n.format("tooltip.stackpack.packer.item",  nbt.getString(NBT_CONFIGURED_ITEM));
        I18n.format("tooltip.stackpack.packer.count", nbt.getInt(NBT_PACKED), MAX_PACK_SIZE);

        super.addInformation(stack, worldIn, tooltip, flagIn);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, PlayerEntity playerIn, Hand handIn)
    {
        final ItemStack stack = playerIn.getHeldItem(handIn);
        final CompoundNBT nbt = stack.getOrCreateTag();

        if(playerIn.isSneaking())
            return this.onShiftRightClick(worldIn, playerIn, handIn, stack, nbt);

        final PackerMode mode = PackerMode.fromValue(nbt.getInt(NBT_MODE));
        switch(mode)
        {
            case CONFIGURE: return this.onConfigure(worldIn, playerIn, handIn, stack, nbt);
            case PACK:      return this.onPack(worldIn, playerIn, handIn, stack, nbt);
            case UNPACK:    return this.onUnpack(worldIn, playerIn, handIn, stack, nbt);
        }

        return super.onItemRightClick(worldIn, playerIn, handIn);
    }

    void sendPlayerMessage(World worldIn, PlayerEntity playerIn, String messageName, Object... args)
    {
        if(worldIn.isRemote())
            playerIn.sendMessage(new TranslationTextComponent(messageName, args));
    }

    ActionResult<ItemStack> onShiftRightClick(World worldIn, PlayerEntity playerIn, Hand handIn, ItemStack stackIn, CompoundNBT nbtIn)
    {
        if(handIn == Hand.OFF_HAND)
            return ActionResult.resultPass(stackIn);

        final PackerMode currentMode = PackerMode.fromValue(nbtIn.getInt(NBT_MODE));
        final PackerMode nextMode    = PackerMode.fromValue(currentMode.getValue() + 1); // Rolls back to CONFIGURE if out of range.

        nbtIn.putInt(NBT_MODE, nextMode.getValue());
        this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.setmode", nextMode.getName());

        return ActionResult.resultPass(stackIn);
    }

    ActionResult<ItemStack> onConfigure(World worldIn, PlayerEntity playerIn, Hand handIn, ItemStack stackIn, CompoundNBT nbtIn)
    {
        if(handIn == Hand.OFF_HAND)
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.error.inoffhand");
            return ActionResult.resultPass(stackIn);
        }

        final ItemStack offHandStack = playerIn.getHeldItemOffhand();
        if(offHandStack.isEmpty())
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.configureinfo");
            return ActionResult.resultPass(stackIn);
        }

        if(offHandStack.getTag() != null)
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.error.hasnbt");
            return ActionResult.resultPass(stackIn);
        }

        final int packedCount = nbtIn.getInt(NBT_PACKED);
        if(packedCount > 0)
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.error.notempty");
            return ActionResult.resultPass(stackIn);
        }

        final String registryName = offHandStack.getItem().getRegistryName().toString();
        nbtIn.putString(NBT_CONFIGURED_ITEM, registryName);
        this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.configuredto", registryName);

        return ActionResult.resultSuccess(stackIn);
    }

    ActionResult<ItemStack> onPack(World worldIn, PlayerEntity playerIn, Hand handIn, ItemStack stackIn, CompoundNBT nbtIn)
    {
        if(handIn == Hand.OFF_HAND)
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.error.inoffhand");
            return ActionResult.resultPass(stackIn);
        }

        final String itemId = nbtIn.getString(NBT_CONFIGURED_ITEM);
        int packedCount     = nbtIn.getInt(NBT_PACKED);

        if(itemId.isEmpty())
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.error.notconfigured");
            return ActionResult.resultPass(stackIn);
        }

        for(int i = 0; i < playerIn.inventory.getSizeInventory(); i++)
        {
            final ItemStack invStack = playerIn.inventory.getStackInSlot(i);

            if(!invStack.getItem().getRegistryName().toString().equals(itemId)
            || invStack.hasTag())
                continue;

            int amountToPack = invStack.getCount();
            if(packedCount + amountToPack > MAX_PACK_SIZE)
                amountToPack = MAX_PACK_SIZE - packedCount;

            if(amountToPack < 0)
                amountToPack = 0;

            invStack.setCount(invStack.getCount() - amountToPack);
            packedCount += amountToPack;
        }

        nbtIn.putInt(NBT_PACKED, packedCount);
        return ActionResult.resultSuccess(stackIn);
    }

    ActionResult<ItemStack> onUnpack(World worldIn, PlayerEntity playerIn, Hand handIn, ItemStack stackIn, CompoundNBT nbtIn)
    {
        final String itemId = nbtIn.getString(NBT_CONFIGURED_ITEM);
        int packedCount     = nbtIn.getInt(NBT_PACKED);

        if(itemId.isEmpty())
        {
            this.sendPlayerMessage(worldIn, playerIn, "msg.stackpack.packer.error.notconfigured");
            return ActionResult.resultPass(stackIn);
        }

        // Only extracting a stack into an empty slot for now, not gonna extract into existing stack for a bit.
        final int emptySlotIndex = this.findFirstEmptySpace(playerIn.inventory);
        if(emptySlotIndex < 0)
            return ActionResult.resultPass(stackIn);

        packedCount -= this.extractToSlot(nbtIn, playerIn.inventory, Integer.MAX_VALUE, emptySlotIndex);
        nbtIn.putInt(NBT_PACKED, packedCount);

        return ActionResult.resultSuccess(stackIn);
    }

    int findFirstEmptySpace(PlayerInventory inventory)
    {
        for(int i = 0; i < inventory.getSizeInventory(); i++)
        {
            ItemStack stack = inventory.getStackInSlot(i);
            if(stack.isEmpty())
                return i;
        }

        return -1;
    }

    int extractToSlot(CompoundNBT nbtIn, PlayerInventory inventory, int count, int slot)
    {
        String itemId   = nbtIn.getString(NBT_CONFIGURED_ITEM);
        int packedCount = nbtIn.getInt(NBT_PACKED);

        ItemStack newStack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId)));
        count              = Math.min(count, packedCount);
        count              = Math.min(count, newStack.getMaxStackSize());

        newStack.setCount(count);
        inventory.setInventorySlotContents(slot, newStack);

        return count;
    }
}
