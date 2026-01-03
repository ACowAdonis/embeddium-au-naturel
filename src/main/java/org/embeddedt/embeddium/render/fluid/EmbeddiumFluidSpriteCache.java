package org.embeddedt.embeddium.render.fluid;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

public class EmbeddiumFluidSpriteCache {
    // Maximum number of cached sprites to prevent unbounded memory growth
    private static final int MAX_CACHE_SIZE = 256;

    // Cache the sprites array to avoid reallocating it on every call
    private final TextureAtlasSprite[] sprites = new TextureAtlasSprite[3];
    // Use LinkedOpenHashMap for LRU eviction capability
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, TextureAtlasSprite> spriteCache = new Object2ObjectLinkedOpenHashMap<>();

    private TextureAtlasSprite getTexture(ResourceLocation identifier) {
        TextureAtlasSprite sprite = spriteCache.getAndMoveToLast(identifier);

        if (sprite == null) {
            sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(identifier);

            // Evict oldest entry if cache is full
            if (spriteCache.size() >= MAX_CACHE_SIZE) {
                spriteCache.removeFirst();
            }

            spriteCache.put(identifier, sprite);
        }

        return sprite;
    }

    /**
     * Clears the sprite cache. Should be called on texture reload.
     */
    public void clearCache() {
        spriteCache.clear();
    }

    public TextureAtlasSprite[] getSprites(BlockAndTintGetter world, BlockPos pos, FluidState fluidState) {
        IClientFluidTypeExtensions fluidExt = IClientFluidTypeExtensions.of(fluidState);
        sprites[0] = getTexture(fluidExt.getStillTexture(fluidState, world, pos));
        sprites[1] = getTexture(fluidExt.getFlowingTexture(fluidState, world, pos));
        ResourceLocation overlay = fluidExt.getOverlayTexture(fluidState, world, pos);
        sprites[2] = overlay != null ? getTexture(overlay) : null;
        return sprites;
    }
}
