# sonarqube-repair

This is the initial draft of the system to repair sonarqube bugs.

To use it, run:

`git clone https://github.com/kth-tcs/sonarqube-repair.git`

`cd sonarqube-repair`

Then open the src/main/java/Main.java file. Change the directory in the statement `launcher.addInputResource("/home/ashutosh/eclipse-workspace/spoon1/source/act/");`
to the source files that you want to repair. Also change the rule in `launcher.addProcessor()` to the rule you want to repair.

Then do :

`mvn -U clean package`

`cd target`

`java -jar sonarqube-repair-0.1-SNAPSHOT-jar-with-dependencies.jar arg1 arg2`

arg1 and arg2 are optional parameters.
Give either 0, 1 or 2 arguments. first argument is sonarqube rule-number which you can get from https://rules.sonarsource.com/java/type/Bug .
Second argument is the projectKey for the sonarqube analysis of source files. for  example "fr.inria.gforge.spoon:spoon-core"
If you don't provide arguments, default value will be used.

The repaired code will appear in ./spooned/ from where the command is run.

Feel free to open issues.
