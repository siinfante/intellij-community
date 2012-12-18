package com.jetbrains.python.sdk;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Comparing;
import com.jetbrains.python.sdk.flavors.CPythonSdkFlavor;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;

import java.util.Comparator;

/**
* @author yole
*/
public class PreferredSdkComparator implements Comparator<Sdk> {
  public static PreferredSdkComparator INSTANCE = new PreferredSdkComparator();

  @Override
  public int compare(Sdk o1, Sdk o2) {
    final PythonSdkFlavor flavor1 = PythonSdkFlavor.getFlavor(o1);
    final PythonSdkFlavor flavor2 = PythonSdkFlavor.getFlavor(o2);
    int venv1weight = PythonSdkType.isVirtualEnv(o1) ? 0 : 1;
    int venv2weight = PythonSdkType.isVirtualEnv(o2) ? 0 : 1;
    if (venv1weight != venv2weight) {
      return venv2weight - venv1weight;
    }
    int flavor1weight = flavor1 instanceof CPythonSdkFlavor ? 1 : 0;
    int flavor2weight = flavor2 instanceof CPythonSdkFlavor ? 1 : 0;
    if (flavor1weight != flavor2weight) {
      return flavor2weight - flavor1weight;
    }
    return -Comparing.compare(o1.getVersionString(), o2.getVersionString());
  }
}
