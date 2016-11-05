#! /usr/bin/perl

use DBI;
use HTTP::Request::Common qw(PUT POST DELETE);
use LWP::UserAgent;
use strict;

$ENV{'PERL_LWP_SSL_VERIFY_HOSTNAME'} = 0;

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


sub getNormalizedMobile
{
  my $usereid = shift;

  ( my $dbname, my $dbhost, my $username, my $password ) = getDbConfig();

  my $dbh = DBI->connect( "DBI:mysql:database=$dbname;host=$dbhost;port=3306", $username, $password )
          || die "Could not connect to database: $DBI::errstr";

  my $sql = "SELECT DISTINCT NORMALIZEDMOBILE from SAKAI_PERSON_T WHERE COMMON_NAME = ?";
  my $sth = $dbh->prepare($sql);

  $sth->execute($usereid);

  my $mobile;

  while (my $row = $sth->fetchrow_hashref) {
     $mobile = $row->{NORMALIZEDMOBILE};
     # print "mobile: $mobile\n";
  }

 return $mobile;
}

sub post_spml
{
 my $host = shift;
 my $spmlbody = shift;

  my $spml = "<soap-env:Envelope xmlns:soap-env=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap-env:Body>" . $spmlbody .
             "</soap-env:Body></soap-env:Envelope>";

  my $url = $host . "/sakai-spml/spmlrouter";

  my $ua= LWP::UserAgent->new;
  $ua->timeout(10);
  $ua->env_proxy;

  my $req = HTTP::Request->new(
      POST => $url);
  $req->content_type('text/xml');
  $req->header('SOAPAction' => '""');
  $req->content($spml);

  my $res = $ua->request($req);

  if ($res->is_error()) {
 	print "request to $url failed\n";
  }

  return ($res->is_success);
}

