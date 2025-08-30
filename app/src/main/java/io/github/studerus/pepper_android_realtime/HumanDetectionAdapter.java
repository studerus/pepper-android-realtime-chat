package io.github.studerus.pepper_android_realtime;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        private final TextView demographics;
        private final TextView distance;
        private final TextView emotion;
        private final TextView smile;
        private final TextView attention;
        private final TextView engagement;
        
        public HumanViewHolder(@NonNull View itemView) {
            super(itemView);
            demographics = itemView.findViewById(R.id.human_demographics);
            distance = itemView.findViewById(R.id.human_distance);
            emotion = itemView.findViewById(R.id.human_emotion);
            smile = itemView.findViewById(R.id.human_smile);
            attention = itemView.findViewById(R.id.human_attention);
            engagement = itemView.findViewById(R.id.human_engagement);
        }
        
        public void bind(PerceptionData.HumanInfo human) {
            // Set demographics
            demographics.setText(human.getDemographics());
            
            // Set distance
            distance.setText(human.getDistanceString());
            
            // Set basic emotion
            emotion.setText(human.getBasicEmotionDisplay());
            
            // Set smile state
            smile.setText(human.getSmileStateDisplay());
            
            // Set attention state
            attention.setText(human.getAttentionLevel());
            
            // Set engagement state
            engagement.setText(human.getEngagementLevel());
        }
    }
}
