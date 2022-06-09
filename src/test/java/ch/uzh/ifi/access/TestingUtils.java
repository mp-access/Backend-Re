package ch.uzh.ifi.access;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.FilePermission;
import ch.uzh.ifi.access.model.constants.TaskType;

public class TestingUtils {

    public static Task createTask() {
        Task task = new Task();
        task.setId(3L);
        task.setMaxPoints(12.0);
        task.setMaxAttempts(5);
        task.setType(TaskType.CODE);
        task.setExtension(Extension.PY);
        TaskFile testMainFile = new TaskFile();
        testMainFile.setName("script.py");
        testMainFile.setPath("public/script.py");
        testMainFile.setExtension(Extension.PY);
        testMainFile.setTask(task);
        testMainFile.setPermission(FilePermission.EDITABLE);
        task.getFiles().add(testMainFile);
        TaskFile testGradingFile = new TaskFile();
        testGradingFile.setName("tests.py");
        testGradingFile.setPath("private/tests.py");
        testGradingFile.setExtension(Extension.PY);
        testGradingFile.setTask(task);
        testGradingFile.setPermission(FilePermission.GRADING);
        testGradingFile.setTemplate(TestingUtils.GRADING_FILE);
        task.getFiles().add(testGradingFile);
        return task;
    }

    public static final String SOLUTION_FILE = """
def fac(n):
    if n == 0:
        return 1
    else:
        return n * fac(n - 1)

""";

    public static final String PARTIAL_SOLUTION_FILE = """
def fac(n):
    return 10

print("fac({}) = {}".format(8, fac(8)))

""";

    public static final String TIMEOUT_SOLUTION_FILE = """
while(True):
    print('Attack!')

""";

    public static final String GRADING_FILE = """
from unittest import TestCase
from public.script import fac
import math

class PrivateTestSuite(TestCase):

    def _assert_fac(self, expected, n):
        actual = fac(n)
        m = "@@The calculation of fac({}) is not correct!@@".format(n)
        self.assertEqual(expected, actual, m)

    def test_imports(self):
        import inspect
        import re
        from public import script
        source = inspect.getsource(script)
        m = "@@The solution seems to import external libraries, please remove all of these.@@"
        for l in source.splitlines():
            self.assertFalse(re.match(r"^\\s*(?:from|import)\\s+(\\w+(?:\\s*,\\s*\\w+)*)", l.strip()), m)

    def test_all(self):
        for n in range(500):
            self._assert_fac(math.factorial(n), n)

""";
}
