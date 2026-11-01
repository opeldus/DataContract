-- The state of a contract at publish time, used as the baseline for change detection.
alter table contract_version add column snapshot varchar(10000000);
