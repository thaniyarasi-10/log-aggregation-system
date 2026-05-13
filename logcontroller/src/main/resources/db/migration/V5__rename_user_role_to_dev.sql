-- V5: Rename USER role to DEV.
-- The Java UserRole enum previously had ADMIN and USER.
-- It now has ADMIN and DEV. This migration renames any existing USER role
-- row in the role table so existing user_role mappings continue to work.
UPDATE "role" SET name = 'DEV' WHERE name = 'USER';
