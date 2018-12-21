package com.bymarcin.openglasses.surface;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import com.bymarcin.openglasses.event.ClientEventHandler;
import com.bymarcin.openglasses.item.OpenGlassesItem;
import com.bymarcin.openglasses.network.NetworkRegistry;
import com.bymarcin.openglasses.network.packet.GlassesEventPacket;
import com.bymarcin.openglasses.surface.widgets.component.face.Text2D;
import com.bymarcin.openglasses.utils.Conditions;
import com.bymarcin.openglasses.utils.Location;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentTranslation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;

import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.bymarcin.openglasses.utils.utilsCommon;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLSync;

@SideOnly(Side.CLIENT)
public class ClientSurface {
	public static ClientSurface instances = new ClientSurface();
	public static ClientEventHandler eventHandler;
	public Map<Integer, IRenderableWidget> renderables = new ConcurrentHashMap<>();
	public Map<Integer, IRenderableWidget> renderablesWorld = new ConcurrentHashMap<>();

	public Conditions conditions = new Conditions();
	
	public OpenGlassesItem glasses;
	public ItemStack glassesStack;
	public Location lastBind;

	public static ScaledResolution resolution = new ScaledResolution(Minecraft.getMinecraft());
	public static ArrayList<Float> renderResolution = null;

	private IRenderableWidget noPowerRender, noLinkRender, widgetLimitRender;

	private ClientSurface() {
		this.noPowerRender = getNoPowerRender();
		this.noLinkRender = getNoLinkRender();
		this.widgetLimitRender = getWidgetLimitRender();
		this.resetLocalGlasses();
	}

	public void resetLocalGlasses(){
		this.removeAllWidgets();
		this.glasses = null;
		this.glassesStack = null;
		this.lastBind = null;
	}

	public void initLocalGlasses(ItemStack glassesStack){
		this.glassesStack = glassesStack;
		this.glasses = (OpenGlassesItem) glassesStack.getItem();
		this.lastBind = utilsCommon.getGlassesTerminalUUID(glassesStack);

		conditions.bufferSensors(this.glassesStack);
	}

	public void refreshConditions(){
		if(glassesStack == null) return;

		conditions.getConditionStates(glassesStack, Minecraft.getMinecraft().player);
	}

	//gets the current widgets and puts them to the correct hashmap
	public void updateWidgets(Set<Entry<Integer, Widget>> widgets){
		for(Entry<Integer, Widget> widget : widgets){
			IRenderableWidget r = widget.getValue().getRenderable();
			switch(r.getRenderType()){
			case GameOverlayLocated:
				renderables.put(widget.getKey(), r);
				break;
			case WorldLocated:
				renderablesWorld.put(widget.getKey(), r);
				break;
			}
		}
	}

	public int getWidgetCount(){
		return (renderables.size() + renderablesWorld.size());
	}

	public void removeWidgets(List<Integer> ids){
		for(Integer id : ids){
			renderables.remove(id);
			renderablesWorld.remove(id);
		}
	}
	
	public void removeAllWidgets(){
		renderables.clear();
		renderablesWorld.clear();
	}

	@SideOnly(Side.CLIENT)
	@SubscribeEvent
	public void onSizeChange(RenderGameOverlayEvent event) {
		ScaledResolution newResolution = event.getResolution();

		if  (newResolution.getScaledWidth() != ClientSurface.resolution.getScaledWidth()
		 || newResolution.getScaledHeight() != ClientSurface.resolution.getScaledHeight()
		 ||  newResolution.getScaleFactor() != ClientSurface.resolution.getScaleFactor()) {
			ClientSurface.resolution = newResolution;
			sendResolution();
		}
	}

	public void sendResolution(){
		if(glasses == null) return;
		if(lastBind == null) return;

		NetworkRegistry.packetHandler.sendToServer(new GlassesEventPacket(GlassesEventPacket.EventType.GLASSES_SCREEN_SIZE, lastBind, Minecraft.getMinecraft().player, ClientSurface.resolution.getScaledWidth(), ClientSurface.resolution.getScaledHeight(), ClientSurface.resolution.getScaleFactor()));
	}

	@SubscribeEvent
	public void onRenderGameOverlay(RenderGameOverlayEvent evt) {
		if (evt.getType() != ElementType.HELMET) return;
		if (!(evt instanceof RenderGameOverlayEvent.Post)) return;

		if(!shouldRenderStart(RenderType.GameOverlayLocated)) return;
		if(renderables.size() < 1) return;

		preRender();

		if(renderResolution != null)
			GlStateManager.scale(ClientSurface.resolution.getScaledWidth() / renderResolution.get(0), ClientSurface.resolution.getScaledHeight() / renderResolution.get(1), 1);

		GlStateManager.depthMask(false);
		renderWidgets(renderables.values());
		postRender(RenderType.GameOverlayLocated);
	}

	@SubscribeEvent
	public void renderWorldLastEvent(RenderWorldLastEvent event)	{
		if(renderablesWorld.size() < 1) return;
		if(!shouldRenderStart(RenderType.WorldLocated)) return;

		double[] playerLocation = getEntityPlayerLocation(Minecraft.getMinecraft().player, event.getPartialTicks());

		preRender();

		GlStateManager.translate(-playerLocation[0], -playerLocation[1], -playerLocation[2]);
		GlStateManager.translate(lastBind.x, lastBind.y, lastBind.z);

		GlStateManager.depthMask(true);
		renderWidgets(renderablesWorld.values());
		postRender(RenderType.WorldLocated);
	}

	void renderWidgets(Collection<IRenderableWidget> widgets){
		String uuid = glassesStack.getTagCompound().getString("userUUID");

		long renderConditions = conditions.get();

		for(IRenderableWidget renderable : widgets) {
			if(!renderable.shouldWidgetBeRendered(Minecraft.getMinecraft().player))
				continue;
			if(!renderable.isWidgetOwner(uuid))
				continue;

			renderWidget(renderable, renderConditions);
		}
	}

	void preRender(){
		GlStateManager.pushMatrix();
	}

	void postRender(RenderType renderType){
		GlStateManager.popMatrix();
		GlStateManager.enableTexture2D();
		if(renderType.equals(RenderType.GameOverlayLocated))
			GlStateManager.disableDepth();
		else
			GlStateManager.enableDepth();
		GlStateManager.depthMask(true);

	}

	void renderWidget(IRenderableWidget widget, long conditionStates){
		GlStateManager.pushMatrix();
		widget.render(Minecraft.getMinecraft().player, lastBind, conditionStates);
		GlStateManager.popMatrix();
	}

	public boolean shouldRenderStart(RenderType renderEvent){
		if(this.glassesStack == null || this.glasses == null)
			return false;

		if(getWidgetCount() > glassesStack.getTagCompound().getInteger("widgetLimit")){
			if(renderEvent.equals(RenderType.GameOverlayLocated) && widgetLimitRender != null) {
				preRender();
				GlStateManager.depthMask(false);
				renderWidget(widgetLimitRender, ~0);
				postRender(RenderType.GameOverlayLocated);
			}
			return false;
		}

		if(glasses.getEnergyStored(glassesStack) == 0){
			if(renderEvent.equals(RenderType.GameOverlayLocated) && noPowerRender != null) {
				preRender();
				GlStateManager.depthMask(false);
				renderWidget(noPowerRender, ~0);
				postRender(RenderType.GameOverlayLocated);
			}
			return false;
		}

		if(lastBind == null) {
			if(renderEvent.equals(RenderType.GameOverlayLocated) && noLinkRender != null) {
				preRender();
				GlStateManager.depthMask(false);
				renderWidget(noLinkRender, ~0);
				postRender(RenderType.GameOverlayLocated);
			}
			return false;
		}

		return true;
	}

	public double[] getEntityPlayerLocation(EntityPlayer e, float partialTicks){
		double x = e.prevPosX + (e.posX - e.prevPosX) * partialTicks;
		double y = e.prevPosY + (e.posY - e.prevPosY) * partialTicks;
		double z = e.prevPosZ + (e.posZ - e.prevPosZ) * partialTicks;
		return new double[]{x, y, z};
	}

	public static RayTraceResult getBlockCoordsLookingAt(EntityPlayer player){
		RayTraceResult objectMouseOver = player.rayTrace(200, 1);
		if(objectMouseOver != null && objectMouseOver.typeOfHit == RayTraceResult.Type.BLOCK){
			return objectMouseOver;
		}
		return null;
	}

	private IRenderableWidget getNoPowerRender(){
		Text2D t = new Text2D();
		t.setText(new TextComponentTranslation("openglasses.infotext.noenergy").getUnformattedText());
		t.WidgetModifierList.addColor(1F, 0F, 0F, 0.5F);
		t.WidgetModifierList.addTranslate(5, 5, 0);
		return t.getRenderable();
	}

	private IRenderableWidget getNoLinkRender(){
		Text2D t = new Text2D();
		t.setText(new TextComponentTranslation("openglasses.infotext.nolink").getUnformattedText());
		t.WidgetModifierList.addColor(1F, 1F, 1F, 0.7F);
		t.WidgetModifierList.addTranslate(5, 5, 0);
		return t.getRenderable();
	}

	private IRenderableWidget getWidgetLimitRender(){
		Text2D t = new Text2D();
		t.setText(new TextComponentTranslation("openglasses.infotext.widgetlimitexhausted").getUnformattedText());
		t.WidgetModifierList.addColor(1F, 1F, 1F, 0.7F);
		t.WidgetModifierList.addTranslate(5, 5, 0);
		return t.getRenderable();
	}

}
