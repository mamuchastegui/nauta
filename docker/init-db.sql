-- Create test database if it doesn't exist
SELECT 'CREATE DATABASE nauta_test'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'nauta_test')\gexec