package io.github.anonymous.pepper_realtime.tools.games;

public class MemoryCard {
    private final String symbol;
    private final int id;
    private boolean isFlipped;
    private boolean isMatched;

    public MemoryCard(int id, String symbol) {
        this.id = id;
        this.symbol = symbol;
        this.isFlipped = false;
        this.isMatched = false;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getId() {
        return id;
    }

    public boolean isFlipped() {
        return isFlipped;
    }

    public boolean isMatched() {
        return isMatched;
    }

    public void setMatched(boolean matched) {
        isMatched = matched;
        if (matched) {
            isFlipped = true; // Matched cards stay flipped
        }
    }

    public boolean canFlip() {
        return !isFlipped && !isMatched;
    }

    public void flip() {
        if (canFlip()) {
            isFlipped = true;
        }
    }

    public void flipBack() {
        if (!isMatched) {
            isFlipped = false;
        }
    }
}
