create table spml_log (
spml_id integer auto_increment,
spml_type varchar(255),
spml_body text,
ipaddress varchar(255),
unixtime timestamp default now(),
PRIMARY KEY (spml_id) )
DEFAULT CHARACTER SET utf8 COLLATE utf8_general_ci;

