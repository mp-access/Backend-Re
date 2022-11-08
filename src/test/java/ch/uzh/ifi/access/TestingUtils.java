package ch.uzh.ifi.access;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskFile;

public class TestingUtils {

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

    public static Task createTask() {
        Task task = new Task();
        task.setId(3L);
        task.setMaxPoints(12.0);
        task.setMaxAttempts(5);
        TaskFile testMainFile = new TaskFile();
        testMainFile.setPath("public/script.py");
        testMainFile.setTask(task);
        testMainFile.setTemplate("print('test')");
        task.getFiles().add(testMainFile);
        TaskFile testGradingFile = new TaskFile();
        testGradingFile.setPath("private/tests.py");
        testGradingFile.setTask(task);
        testGradingFile.setTemplate(TestingUtils.GRADING_FILE);
        task.getFiles().add(testGradingFile);
        return task;
    }
}
