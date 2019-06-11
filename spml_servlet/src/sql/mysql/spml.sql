create table spml_log (
spml_id integer auto_increment,
spml_type varchar(255),
spml_body text,
userEid varchar(255),
ipaddress varchar(255),
unixtime timestamp default now(),
PRIMARY KEY (spml_id) )
DEFAULT CHARACTER SET utf8 COLLATE utf8_general_ci;

CREATE TABLE UCT_ORG (

Description varchar(255),
org varchar(3),
org_unit int);

CREATE TABLE SPML_UPDATED_USERS (
  id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  userEid varchar(255) DEFAULT NULL,
  dateQueued datetime DEFAULT NULL,
  UNIQUE KEY id (id),
  KEY SPML_UDPATED_USERS_userEid_idx (userEid),
  KEY SPML_UDPATED_USERS_EID_DATE (userEid,dateQueued)
);

CREATE TABLE SPML_WSDL_IN (
  userId varchar(255) DEFAULT NULL,
  courseEid varchar(255) DEFAULT NULL,
  queued timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY userId_i (userId)
);
