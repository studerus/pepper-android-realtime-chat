package io.github.anonymous.pepper_realtime;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying detected humans in the perception dashboard
 */
public class HumanDetectionAdapter extends RecyclerView.Adapter<HumanDetectionAdapter.HumanViewHolder> {
    
    private final List<PerceptionData.HumanInfo> humans = new ArrayList<>();
    
    public void updateHumans(List<PerceptionData.HumanInfo> newHumans) {
        // Use granular notifications based on size delta
        int oldSize = this.humans.size();
        int newSize = (newHumans == null) ? 0 : newHumans.size();

        if (newHumans == null || newHumans.isEmpty()) {
            // Remove all items if any
            if (oldSize > 0) {
                this.humans.clear();
                notifyItemRangeRemoved(0, oldSize);
            }
            return;
        }

        // Replace backing list
        this.humans.clear();
        this.humans.addAll(newHumans);

        if (oldSize == 0) {
            notifyItemRangeInserted(0, newSize);
        } else if (newSize == oldSize) {
            notifyItemRangeChanged(0, newSize);
        } else if (newSize > oldSize) {
            notifyItemRangeChanged(0, oldSize);
            notifyItemRangeInserted(oldSize, newSize - oldSize);
        } else { // newSize < oldSize
            notifyItemRangeChanged(0, newSize);
            notifyItemRangeRemoved(newSize, oldSize - newSize);
        }
    }
    
    @NonNull
    @Override
    public HumanViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_human_detection, parent, false);
        return new HumanViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull HumanViewHolder holder, int position) {
        PerceptionData.HumanInfo human = humans.get(position);
        holder.bind(human);
    }
    
    @Override
    public int getItemCount() {
        return humans.size();
    }
    
    static class HumanViewHolder extends RecyclerView.ViewHolder {
        private final ImageView facePicture;
        private final TextView demographics;
        private final TextView distance;
        private final TextView emotion;
        private final TextView pleasure;
        private final TextView excitement;
        private final TextView smile;
        private final TextView attention;
        private final TextView engagement;
        // Azure UI Elements
        private final TextView azureHeadPose;
        private final TextView azureGlasses;
        private final TextView azureMask;
        private final TextView azureQuality;
        private int lastW = -1, lastH = -1;
        
        public HumanViewHolder(@NonNull View itemView) {
            super(itemView);
            facePicture = itemView.findViewById(R.id.human_face_picture);
            demographics = itemView.findViewById(R.id.human_demographics);
            distance = itemView.findViewById(R.id.human_distance);
            emotion = itemView.findViewById(R.id.human_emotion);
            pleasure = itemView.findViewById(R.id.human_pleasure);
            excitement = itemView.findViewById(R.id.human_excitement);
            smile = itemView.findViewById(R.id.human_smile);
            attention = itemView.findViewById(R.id.human_attention);
            engagement = itemView.findViewById(R.id.human_engagement);
            // Azure Views
            azureHeadPose = itemView.findViewById(R.id.azure_head_pose);
            azureGlasses = itemView.findViewById(R.id.azure_glasses);
            azureMask = itemView.findViewById(R.id.azure_mask);
            azureQuality = itemView.findViewById(R.id.azure_quality);
        }
        
        public void bind(PerceptionData.HumanInfo human) {
            // Set face picture with debugging
            if (human.facePicture != null) {
                facePicture.setImageBitmap(human.facePicture);
                int w = human.facePicture.getWidth();
                int h = human.facePicture.getHeight();
                if (w != lastW || h != lastH) {
                    android.util.Log.d("HumanAdapter", "🖼️ Displaying face picture for human " + human.id +
                        " (" + w + "x" + h + ")");
                    lastW = w; lastH = h;
                }
            } else {
                // Use default placeholder 
                facePicture.setImageResource(android.R.drawable.ic_menu_gallery);
                android.util.Log.d("HumanAdapter", "🖼️ Using placeholder for human " + human.id + " (no face picture available)");
            }
            
            // Set demographics
            demographics.setText(human.getDemographics());
            
            // Set distance
            distance.setText(human.getDistanceString());
            
            // Set basic emotion
            emotion.setText(human.getBasicEmotionDisplay());
            
            // Set pleasure state
            pleasure.setText(human.getPleasureStateDisplay());
            
            // Set excitement state
            excitement.setText(human.getExcitementStateDisplay());
            
            // Set smile state
            smile.setText(human.getSmileStateDisplay());
            
            // Set attention state
            attention.setText(human.getAttentionLevel());
            
            // Set engagement state
            engagement.setText(human.getEngagementLevel());

            // --- Bind Azure Data ---
            azureHeadPose.setText(formatHeadPose(human.azureYawDeg));
            azureGlasses.setText(String.format("Glasses: %s", human.glassesType));
            azureMask.setText(formatMask(human.isMasked));
            azureQuality.setText(String.format("Quality: %s", human.imageQuality));
        }

        private String formatHeadPose(Double yawDeg) {
            if (yawDeg == null) {
                return "Pose: N/A";
            }
            String direction;
            if (yawDeg < -10.0) {
                direction = "Right";
            } else if (yawDeg > 10.0) {
                direction = "Left";
            } else {
                direction = "Forward";
            }
            return String.format(java.util.Locale.US, "Pose: %s (%.0f°)", direction, yawDeg);
        }

        private String formatMask(Boolean isMasked) {
            if (isMasked == null) {
                return "Mask: N/A";
            }
            return "Mask: " + (isMasked ? "Yes" : "No");
        }
    }
}
