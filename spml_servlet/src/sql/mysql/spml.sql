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