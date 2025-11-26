package io.github.anonymous.pepper_realtime.tools.entertainment;

import android.util.Log;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.builder.AnimateBuilder;
import com.aldebaran.qi.sdk.builder.AnimationBuilder;
import com.aldebaran.qi.sdk.object.actuation.Animation;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.tools.BaseTool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool for playing preinstalled Pepper animations.
 * Supports various emotional and gestural animations.
 */
public class PlayAnimationTool extends BaseTool {
    
    private static final String TAG = "PlayAnimationTool";

    @Override
    public String getName() {
        return "play_animation";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Play a preinstalled Pepper animation. Use the hello_01 animation when the user wants you to wave or say hello.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            // Available animations
            JSONArray animationEnums = new JSONArray()
                    .put("applause_01")
                    .put("bowshort_01")
                    .put("funny_01")
                    .put("happy_01")
                    .put("hello_01")
                    .put("hey_02")
                    .put("kisses_01")
                    .put("laugh_01")
                    .put("showfloor_01")
                    .put("showsky_01")
                    .put("showtablet_02")
                    .put("wings_01");
            
            JSONObject properties = new JSONObject();
            properties.put("name", new JSONObject()
                .put("type", "string")
                .put("description", "Animation identifier.")
                .put("enum", animationEnums));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("name"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String name = args.optString("name", "");
        if (name.isEmpty()) {
            return new JSONObject().put("error", "Missing required parameter: name").toString();
        }
        
        Integer resId = mapAnimationNameToResId(name);
        if (resId == null) {
            return new JSONObject().put("error", "Unsupported animation name: " + name).toString();
        }
        
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "QiContext not ready").toString();
        }
        
        try {
            // Start animation asynchronously
            Future<Animation> animFuture = AnimationBuilder.with((com.aldebaran.qi.sdk.QiContext) context.getQiContext())
                    .withResources(resId)
                    .buildAsync();
            
            animFuture.andThenCompose(animation ->
                    AnimateBuilder.with((com.aldebaran.qi.sdk.QiContext) context.getQiContext())
                            .withAnimation(animation)
                            .build()
                            .async()
                            .run()
            ).thenConsume(future -> {
                if (future.hasError()) {
                    Log.e(TAG, "Animation failed", future.getError());
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting animation", e);
        }
        
        return new JSONObject()
                .put("status", "started")
                .put("name", name)
                .toString();
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String getApiKeyType() {
        return null;
    }

    /**
     * Map animation name to Android resource ID
     */
    @SuppressWarnings("SpellCheckingInspection") // Animation names like bowshort, showfloor, etc.
    private Integer mapAnimationNameToResId(String name) {
        switch (name) {
            case "applause_01": return R.raw.applause_01;
            case "bowshort_01": return R.raw.bowshort_01;
            case "funny_01": return R.raw.funny_01;
            case "happy_01": return R.raw.happy_01;
            case "hello_01": return R.raw.hello_01;
            case "hey_02": return R.raw.hey_02;
            case "kisses_01": return R.raw.kisses_01;
            case "laugh_01": return R.raw.laugh_01;
            case "showfloor_01": return R.raw.showfloor_01;
            case "showsky_01": return R.raw.showsky_01;
            case "showtablet_02": return R.raw.showtablet_02;
            case "wings_01": return R.raw.wings_01;
            default: return null;
        }
    }
}
