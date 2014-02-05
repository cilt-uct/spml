#! /usr/bin/perl

use DBI;
use strict;

require '/usr/local/sakaiconfig/vula_auth.pl';

# Get the user's set of course memberships
sub getCmCourses
{
  my $usereid = shift;

  ( my $dbname, my $dbhost, my $username, my $password ) = getDbConfig();

  my $dbh = DBI->connect( "DBI:mysql:database=$dbname;host=$dbhost;port=3306", $username, $password )
          || die "Could not connect to database: $DBI::errstr";

  my $sql = "SELECT enterprise_id FROM CM_MEMBERSHIP_T M INNER JOIN CM_MEMBER_CONTAINER_T C ON M.MEMBER_CONTAINER_ID = C.MEMBER_CONTAINER_ID  WHERE C.category='course' and USER_ID = ?";
  my $sth = $dbh->prepare($sql);

  $sth->execute($usereid);

  my %courses;

  while (my $row = $sth->fetchrow_hashref) {
     print "$usereid: " . $row->{enterprise_id} . "\n";
     $courses{$row->{enterprise_id}}=1;
  }

  $sth->finish();

  return %courses;
}

