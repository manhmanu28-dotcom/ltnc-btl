-- =========================================================================
-- SCHEMA CSDL HỆ THỐNG ĐẤU GIÁ TRỰC TUYẾN (ONLINE AUCTION SYSTEM)
-- Quản trị dữ liệu bền vững (Data Persistence) - SQLite / MySQL
-- =========================================================================

PRAGMA foreign_keys = ON;

-- 1. Bảng người dùng (users)
-- Hỗ trợ đa hình: BIDDER (số dư balance), SELLER (điểm uy tín rating), ADMIN (quyền quản trị)
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    email TEXT,
    role TEXT CHECK(role IN ('BIDDER', 'SELLER', 'ADMIN')) NOT NULL,
    balance REAL DEFAULT 0.0,
    rating REAL DEFAULT 5.0,
    is_locked INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Bảng sản phẩm đấu giá (items)
-- Áp dụng Factory Method Pattern & tính đa hình qua category và extra_attributes (JSON)
CREATE TABLE IF NOT EXISTS items (
    id TEXT PRIMARY KEY,
    seller_id TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    starting_price REAL NOT NULL CHECK(starting_price >= 0),
    category TEXT CHECK(category IN ('ELECTRONICS', 'ART', 'VEHICLE', 'OTHER')) NOT NULL,
    extra_attributes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. Bảng phiên đấu giá (auctions)
-- Điều khiển vòng đời: OPEN -> RUNNING -> FINISHED -> PAID / CANCELED
CREATE TABLE IF NOT EXISTS auctions (
    id TEXT PRIMARY KEY,
    item_id TEXT NOT NULL,
    seller_id TEXT NOT NULL,
    min_increment REAL NOT NULL DEFAULT 50000.0,
    current_price REAL NOT NULL,
    current_winner_id TEXT,
    current_winner_username TEXT,
    status TEXT CHECK(status IN ('OPEN', 'RUNNING', 'FINISHED', 'PAID', 'CANCELED')) DEFAULT 'OPEN',
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    anti_sniping_trigger INTEGER DEFAULT 30,
    anti_sniping_extend INTEGER DEFAULT 60,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE,
    FOREIGN KEY (seller_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (current_winner_id) REFERENCES users(id) ON DELETE SET NULL
);

-- 4. Bảng lịch sử các lượt đặt giá (bid_transactions)
-- Lưu trữ chi tiết tất cả giao dịch đặt giá theo thứ tự thời gian
CREATE TABLE IF NOT EXISTS bid_transactions (
    id TEXT PRIMARY KEY,
    auction_id TEXT NOT NULL,
    bidder_id TEXT NOT NULL,
    bidder_username TEXT NOT NULL,
    bid_amount REAL NOT NULL,
    bid_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE,
    FOREIGN KEY (bidder_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Chỉ mục tối ưu tốc độ truy vấn
CREATE INDEX IF NOT EXISTS idx_auctions_status ON auctions(status);
CREATE INDEX IF NOT EXISTS idx_auctions_seller ON auctions(seller_id);
CREATE INDEX IF NOT EXISTS idx_bids_auction ON bid_transactions(auction_id);
CREATE INDEX IF NOT EXISTS idx_bids_bidder ON bid_transactions(bidder_id);
