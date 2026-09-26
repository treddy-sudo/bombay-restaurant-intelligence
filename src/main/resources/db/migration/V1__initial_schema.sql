CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(80) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  group_name VARCHAR(80) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE vendors (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(160) NOT NULL,
  normalized_name VARCHAR(160) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE employees (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(160) NOT NULL,
  normalized_name VARCHAR(160) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE normalization_mappings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  raw_term VARCHAR(255) NOT NULL,
  canonical_type VARCHAR(80) NOT NULL,
  canonical_value VARCHAR(255) NOT NULL,
  confidence NUMERIC(5,4) NOT NULL DEFAULT 1.0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_normalization_mapping UNIQUE(raw_term, canonical_type)
);
CREATE TABLE source_messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_message_id VARCHAR(255) NOT NULL UNIQUE,
  sender VARCHAR(255),
  message_type VARCHAR(50) NOT NULL,
  raw_text TEXT,
  received_at TIMESTAMPTZ NOT NULL,
  payload_json TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE source_documents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  filename VARCHAR(512) NOT NULL,
  content_type VARCHAR(160),
  file_checksum VARCHAR(64) NOT NULL UNIQUE,
  storage_location VARCHAR(1024),
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE ingestion_jobs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_type VARCHAR(80) NOT NULL,
  source_reference VARCHAR(512),
  status VARCHAR(50) NOT NULL,
  checksum VARCHAR(64),
  payload_json TEXT,
  record_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE source_column_mappings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_key VARCHAR(255) NOT NULL,
  source_column VARCHAR(255) NOT NULL,
  canonical_field VARCHAR(80) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_source_column_mapping UNIQUE(source_key, source_column)
);
CREATE TABLE transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  business_date DATE NOT NULL,
  transaction_type VARCHAR(80) NOT NULL,
  category_id UUID REFERENCES categories(id),
  vendor_id UUID REFERENCES vendors(id),
  employee_id UUID REFERENCES employees(id),
  amount NUMERIC(19,2) NOT NULL,
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  description TEXT,
  source_type VARCHAR(80) NOT NULL,
  source_reference VARCHAR(512),
  source_message_id VARCHAR(255),
  source_filename VARCHAR(512),
  sender VARCHAR(255),
  received_at TIMESTAMPTZ,
  raw_text TEXT,
  original_file_location VARCHAR(1024),
  file_checksum VARCHAR(64),
  extraction_confidence NUMERIC(5,4),
  normalization_confidence NUMERIC(5,4),
  normalized_fingerprint VARCHAR(64),
  status VARCHAR(50) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_transactions_source_message ON transactions(source_message_id) WHERE source_message_id IS NOT NULL;
CREATE UNIQUE INDEX uq_transactions_fingerprint ON transactions(normalized_fingerprint) WHERE normalized_fingerprint IS NOT NULL;
CREATE INDEX idx_transactions_business_date_status ON transactions(business_date, status);
CREATE INDEX idx_transactions_vendor ON transactions(vendor_id, business_date);
CREATE INDEX idx_transactions_category ON transactions(category_id, business_date);

CREATE TABLE review_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id UUID REFERENCES transactions(id),
  reason VARCHAR(500) NOT NULL,
  candidate_json TEXT,
  status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMPTZ,
  resolution_note TEXT
);
CREATE TABLE audit_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entity_type VARCHAR(80) NOT NULL,
  entity_id UUID NOT NULL,
  old_value TEXT,
  new_value TEXT,
  changed_by VARCHAR(160) NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reason TEXT
);
CREATE TABLE daily_metrics (
  metric_date DATE NOT NULL,
  metric_key VARCHAR(120) NOT NULL,
  amount NUMERIC(19,2) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(metric_date, metric_key)
);

INSERT INTO categories(code,name,group_name) VALUES
('IN_STORE_SALES','In-store sales','SALES'),('ONLINE_SALES','Online sales','SALES'),('CASH_SALES','Cash sales','SALES'),('UPI_SALES','UPI sales','SALES'),('ZOMATO_SALES','Zomato sales','SALES'),('SWIGGY_SALES','Swiggy sales','SALES'),
('VEGETABLES','Vegetables','PURCHASES'),('DAIRY','Dairy','PURCHASES'),('GROCERIES','Groceries','PURCHASES'),('MASALA','Masala','PURCHASES'),('OIL','Oil','PURCHASES'),('GAS','Gas','PURCHASES'),('PACKAGING','Packaging','PURCHASES'),('WATER','Water','PURCHASES'),('BEVERAGES','Beverages','PURCHASES'),('BAKERY','Bakery','PURCHASES'),('FROZEN_ITEMS','Frozen items','PURCHASES'),('OTHER_PURCHASES','Other purchases','PURCHASES'),
('RENT','Rent','OPERATING_EXPENSE'),('ELECTRICITY','Electricity','OPERATING_EXPENSE'),('MAINTENANCE','Maintenance','OPERATING_EXPENSE'),('OTHER_EXPENSE','Other expense','OPERATING_EXPENSE'),
('EMPLOYEE_SALARY','Employee salary','EMPLOYEES'),('EMPLOYEE_ADVANCE','Employee advance','EMPLOYEES'),
('ZOMATO_ADVERTISING','Zomato advertising','ADVERTISING'),('SWIGGY_ADVERTISING','Swiggy advertising','ADVERTISING'),('OTHER_ADVERTISING','Other advertising','ADVERTISING'),
('ZOMATO_COMMISSION','Zomato commission','AGGREGATOR_DEDUCTION'),('SWIGGY_COMMISSION','Swiggy commission','AGGREGATOR_DEDUCTION'),('RESTAURANT_DISCOUNT','Restaurant discount','AGGREGATOR_DEDUCTION'),('PLATFORM_FEES','Platform fees','AGGREGATOR_DEDUCTION'),('TAX','Tax','AGGREGATOR_DEDUCTION');

INSERT INTO normalization_mappings(raw_term,canonical_type,canonical_value,confidence) VALUES
('veg','CATEGORY','VEGETABLES',1),('vegetable','CATEGORY','VEGETABLES',1),('vegetables','CATEGORY','VEGETABLES',1),('sabji','CATEGORY','VEGETABLES',1),
('staff advance','CATEGORY','EMPLOYEE_ADVANCE',1),('employee advance','CATEGORY','EMPLOYEE_ADVANCE',1),('salary advance','CATEGORY','EMPLOYEE_ADVANCE',1),
('zomato ads','CATEGORY','ZOMATO_ADVERTISING',1),('advertising spend','CATEGORY','ZOMATO_ADVERTISING',0.9),('promotion spend','CATEGORY','ZOMATO_ADVERTISING',0.9),('investment in growth','CATEGORY','ZOMATO_ADVERTISING',0.95);
