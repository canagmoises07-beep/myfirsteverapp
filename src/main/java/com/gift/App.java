package com.gift;

import io.javalin.Javalin;
import com.google.gson.Gson;
import java.sql.*;
import java.util.*;

public class App {
    public static void main(String[] args) {
        // Initialize Database
        Database.init();

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
        }).start(8080);

        Gson gson = new Gson();

        // 1. Get all participants and claim status
        app.get("/api/names", ctx -> {
            List list = new ArrayList();
            try (Connection conn = Database.connect();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, gifter_pin FROM participants")) {
                while (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", rs.getString("name"));
                    map.put("isClaimed", rs.getString("gifter_pin") != null);
                    list.add(map);
                }
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("error", e.getMessage()));
                return;
            }
            ctx.json(list);
        });

        // 2. Register or Update Own Wishlist
        app.post("/api/my-wishlist", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String name = body.getOrDefault("name", "").trim();
            String pin = body.getOrDefault("pin", "").trim();
            String wishlist = body.getOrDefault("wishlist", "").trim();

            if (name.isEmpty() || pin.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Name and Personal PIN are required."));
                return;
            }

            try (Connection conn = Database.connect()) {
                String checkSql = "SELECT owner_pin FROM participants WHERE name = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
                    pstmt.setString(1, name);
                    ResultSet rs = pstmt.executeQuery();

                    if (rs.next()) {
                        String existingPin = rs.getString("owner_pin");
                        if (!existingPin.equals(pin)) {
                            ctx.status(401).json(Map.of("error", "Incorrect Personal PIN for this name!"));
                            return;
                        }
                        String updateSql = "UPDATE participants SET wishlist = ? WHERE name = ?";
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                            updateStmt.setString(1, wishlist);
                            updateStmt.setString(2, name);
                            updateStmt.executeUpdate();
                        }
                    } else {
                        String insertSql = "INSERT INTO participants (name, owner_pin, wishlist) VALUES (?, ?, ?)";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                            insertStmt.setString(1, name);
                            insertStmt.setString(2, pin);
                            insertStmt.setString(3, wishlist);
                            insertStmt.executeUpdate();
                        }
                    }
                }
                ctx.json(Map.of("message", "Wishlist saved successfully!"));
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            }
        });

        // 3. View Recipient Wishlist (Strictly locked to one recipient per Gifter PIN)
        app.post("/api/view-recipient", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String targetName = body.getOrDefault("targetName", "").trim();
            String gifterPin = body.getOrDefault("gifterPin", "").trim();

            if (targetName.isEmpty() || gifterPin.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Target name and Gifter PIN are required."));
                return;
            }

            try (Connection conn = Database.connect()) {
                // 1. Check if this exact Gifter PIN has ALREADY claimed someone else
                String checkPinSql = "SELECT name FROM participants WHERE gifter_pin = ? AND name != ?";
                try (PreparedStatement pstmt = conn.prepareStatement(checkPinSql)) {
                    pstmt.setString(1, gifterPin);
                    pstmt.setString(2, targetName);
                    ResultSet rsPin = pstmt.executeQuery();
                    if (rsPin.next()) {
                        String alreadyClaimed = rsPin.getString("name");
                        ctx.status(400).json(Map.of("error", "This Gifter PIN is already locked to " + alreadyClaimed + "! You can only unwrap one recipient."));
                        return;
                    }
                }

                // 2. Check the target participant record
                String sql = "SELECT wishlist, gifter_pin FROM participants WHERE name = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, targetName);
                    ResultSet rs = pstmt.executeQuery();

                    if (!rs.next()) {
                        ctx.status(404).json(Map.of("error", "Participant not found."));
                        return;
                    }

                    String storedGifterPin = rs.getString("gifter_pin");
                    String wishlist = rs.getString("wishlist");

                    if (storedGifterPin == null) {
                        // Unclaimed: Lock it to this Gifter PIN for the first time
                        String lockSql = "UPDATE participants SET gifter_pin = ? WHERE name = ?";
                        try (PreparedStatement lockStmt = conn.prepareStatement(lockSql)) {
                            lockStmt.setString(1, gifterPin);
                            lockStmt.setString(2, targetName);
                            lockStmt.executeUpdate();
                        }
                        ctx.json(Map.of("wishlist", wishlist != null && !wishlist.isEmpty() ? wishlist : "(No wishlist provided yet)", "status", "Successfully claimed and locked to your Gifter PIN!"));
                    } else {
                        // Already claimed: Verify it matches
                        if (storedGifterPin.equals(gifterPin)) {
                            ctx.json(Map.of("wishlist", wishlist != null && !wishlist.isEmpty() ? wishlist : "(No wishlist provided yet)", "status", "Unlocked with your Gifter PIN."));
                        } else {
                            ctx.status(401).json(Map.of("error", "This recipient is already locked by another Gifter PIN!"));
                        }
                    }
                }
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            }
        });
        // 4. Admin Reset Claim
        app.post("/api/admin/reset-claim", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String adminPass = body.getOrDefault("adminPass", "").trim();
            String targetName = body.getOrDefault("resetName", "").trim();

            if (!adminPass.equals("moises2026")) {
                ctx.status(401).json(Map.of("error", "Invalid admin password."));
                return;
            }

            try (Connection conn = Database.connect()) {
                String sql = "UPDATE participants SET gifter_pin = NULL WHERE name = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, targetName);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        ctx.json(Map.of("message", "Successfully reset lock for " + targetName));
                    } else {
                        ctx.status(404).json(Map.of("error", "Participant not found."));
                    }
                }
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("error", "Database error: " + e.getMessage()));
            }
        });
    }
}