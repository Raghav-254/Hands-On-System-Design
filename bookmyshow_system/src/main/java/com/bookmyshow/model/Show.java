package com.bookmyshow.model;

import java.util.List;

public class Show {
    private final String showId;
    private final String movieTitle;
    private final String venue;
    private final String screen;
    private final String showTime;
    private final List<Seat> seats;

    public Show(String showId, String movieTitle, String venue, String screen,
                String showTime, List<Seat> seats) {
        this.showId = showId;
        this.movieTitle = movieTitle;
        this.venue = venue;
        this.screen = screen;
        this.showTime = showTime;
        this.seats = seats;
    }

    public String getShowId() { return showId; }
    public String getMovieTitle() { return movieTitle; }
    public String getVenue() { return venue; }
    public String getScreen() { return screen; }
    public String getShowTime() { return showTime; }
    public List<Seat> getSeats() { return seats; }

    @Override
    public String toString() {
        return String.format("Show[%s '%s' at %s %s, %s, %d seats]",
            showId, movieTitle, venue, screen, showTime, seats.size());
    }
}
