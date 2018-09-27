This is a version of an SPML client Servelet written by David Horwitz at the
University of Cape Town. Please note that this is pre release software.

To build the servelt you need to add the OpenSPML jars to your maven
repostitory, as they are not in any of the remote repositories. They can be
downloaded from http://openspml.org. Once you have downloaded the
files:

* Create a folder in you maven repository (~/maven/repository on nix) called 'openspml'
* in that folder create a folder called jars
* copy the jars from the lib folder of the openspml tarball to thefolder in (2)
* Reman openspml.jar to openspml-0.5.jar
	

You should now be able to build the packagae as any other Sakai tool.  The
SPML service is exposed at http://[sakai-url]/sakai-spml/spmlrouter

NOTE:
1) The admin username and password are hardcoded into the java source and the
sevelet will trust any source submiting valid SPML requests. This should not
be used in production!

